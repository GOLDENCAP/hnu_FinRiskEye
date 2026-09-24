#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr const char* kRemoteDbDir = "/data/user/0/com.example.chapter07/databases";
constexpr const char* kTempPullDir = "/tmp/chapter07_adb_click";
constexpr int kTapIntervalMs = 500;

struct DbInfo {
    std::string name;
    std::string remotePath;
};

struct ClickRecord {
    int id = 0;
    int x = 0;
    int y = 0;
    std::string timestamp;
};

std::string trim(const std::string& input) {
    const auto begin = input.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) {
        return "";
    }
    const auto end = input.find_last_not_of(" \t\r\n");
    return input.substr(begin, end - begin + 1);
}

std::string shellEscapeSingleQuotes(const std::string& value) {
    std::string escaped;
    escaped.reserve(value.size() + 8);
    for (char ch : value) {
        if (ch == '\'') {
            escaped += "'\\''";
        } else {
            escaped += ch;
        }
    }
    return escaped;
}

std::string runAndCapture(const std::string& command) {
    std::array<char, 4096> buffer{};
    std::string output;
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) {
        throw std::runtime_error("无法执行命令: " + command);
    }
    while (fgets(buffer.data(), static_cast<int>(buffer.size()), pipe) != nullptr) {
        output += buffer.data();
    }
    const int exitCode = pclose(pipe);
    if (exitCode != 0) {
        throw std::runtime_error("命令执行失败: " + command + "\n输出:\n" + output);
    }
    return output;
}

void runOrThrow(const std::string& command) {
    const int exitCode = std::system(command.c_str());
    if (exitCode != 0) {
        throw std::runtime_error("命令执行失败: " + command);
    }
}

void ensureAdbReady() {
    std::cout << "检查 adb 设备状态..." << std::endl;
    runOrThrow("adb get-state >/dev/null 2>&1");
}

std::vector<DbInfo> listRemoteDatabases() {
    const std::string command =
        "adb shell su 0 sh -c 'for f in " + std::string(kRemoteDbDir) + "/*.db; do [ -e \"$f\" ] && basename \"$f\"; done'";
    std::string output = runAndCapture(command);
    std::vector<DbInfo> databases;
    std::istringstream stream(output);
    std::string line;
    while (std::getline(stream, line)) {
        std::string fileName = trim(line);
        if (fileName.empty()) {
            continue;
        }
        databases.push_back(DbInfo{fileName, std::string(kRemoteDbDir) + "/" + fileName});
    }
    return databases;
}

int chooseDatabase(const std::vector<DbInfo>& databases) {
    std::cout << "可选数据库:\n";
    for (std::size_t i = 0; i < databases.size(); ++i) {
        std::cout << "  [" << i + 1 << "] " << databases[i].remotePath << '\n';
    }
    std::cout << "请输入序号: ";
    int selected = 0;
    std::cin >> selected;
    if (!std::cin || selected < 1 || selected > static_cast<int>(databases.size())) {
        throw std::runtime_error("无效的数据库序号");
    }
    return selected - 1;
}

fs::path pullDatabaseToLocal(const DbInfo& dbInfo) {
    fs::create_directories(kTempPullDir);
    fs::path localPath = fs::path(kTempPullDir) / dbInfo.name;
    const std::string escapedRemotePath = shellEscapeSingleQuotes(dbInfo.remotePath);
    const std::string command =
        "adb exec-out su 0 cat '" + escapedRemotePath + "' > '" + localPath.string() + "'";
    std::cout << "拉取数据库到本地: " << localPath << std::endl;
    runOrThrow(command);
    return localPath;
}

std::vector<ClickRecord> loadRecordsFromSqlite(const fs::path& localDbPath) {
    const std::string sql =
        "SELECT _id, x_coordinate, y_coordinate, timestamp FROM click_records ORDER BY timestamp DESC;";
    const std::string command =
        "sqlite3 -csv '" + shellEscapeSingleQuotes(localDbPath.string()) + "' \"" + sql + "\"";
    std::string output = runAndCapture(command);

    std::vector<ClickRecord> records;
    std::istringstream stream(output);
    std::string line;
    while (std::getline(stream, line)) {
        std::string row = trim(line);
        if (row.empty()) {
            continue;
        }

        std::vector<std::string> columns;
        std::string cell;
        std::istringstream rowStream(row);
        while (std::getline(rowStream, cell, ',')) {
            columns.push_back(cell);
        }
        if (columns.size() < 4) {
            continue;
        }

        ClickRecord record;
        record.id = std::stoi(columns[0]);
        record.x = std::stoi(columns[1]);
        record.y = std::stoi(columns[2]);
        record.timestamp = columns[3];
        records.push_back(record);
    }
    return records;
}

void replayTaps(const std::vector<ClickRecord>& records) {
    std::cout << "开始回放，共 " << records.size() << " 条记录，每 0.5 秒点击一次。" << std::endl;
    for (std::size_t i = 0; i < records.size(); ++i) {
        const ClickRecord& record = records[i];
        std::ostringstream command;
        command << "adb shell su 0 input tap " << record.x << ' ' << record.y;
        std::cout << '[' << (i + 1) << '/' << records.size() << "] 点击 ("
                  << record.x << ", " << record.y << ")";
        if (!record.timestamp.empty()) {
            std::cout << " timestamp=" << record.timestamp;
        }
        std::cout << std::endl;
        runOrThrow(command.str());
        std::this_thread::sleep_for(std::chrono::milliseconds(kTapIntervalMs));
    }
    std::cout << "回放完成。" << std::endl;
}

} // namespace

int main() {
    try {
        ensureAdbReady();
        std::vector<DbInfo> databases = listRemoteDatabases();
        if (databases.empty()) {
            std::cerr << "未找到任何 .db 数据库，请确认设备上存在 " << kRemoteDbDir << std::endl;
            return 1;
        }

        const int selectedIndex = chooseDatabase(databases);
        const DbInfo& selectedDb = databases[selectedIndex];
        std::cout << "已选择: " << selectedDb.remotePath << std::endl;

        const fs::path localDbPath = pullDatabaseToLocal(selectedDb);
        std::vector<ClickRecord> records = loadRecordsFromSqlite(localDbPath);
        if (records.empty()) {
            std::cerr << "数据库中没有读取到 click_records 坐标数据。" << std::endl;
            return 1;
        }

        replayTaps(records);
        return 0;
    } catch (const std::exception& ex) {
        std::cerr << "错误: " << ex.what() << std::endl;
        return 1;
    }
}
