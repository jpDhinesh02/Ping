# Ping Monitor

A desktop GUI application to monitor server availability via ICMP ping.  
Displays real-time up/down status charts for each server with live updates every 5 seconds.

---

## Screenshots

> Sidebar shows grouped servers with red highlight + warning icon for unreachable hosts.  
> Main panel shows live time-series charts (green = up, red = down).

---

## Features

- Real-time ping status for multiple servers (every 5 seconds)
- Servers grouped by category in a collapsible tree sidebar
- Live time-series charts per server (JFreeChart)
- **Add server** — "+ Add Server" button with IP + group selection
- **Remove server** — right-click any server → Remove Server
- Server list persisted to `servers.conf` — survives restarts
- Works fully offline (uses system `ping` binary)
- Dark theme (FlatLaf)
- Cross-platform: Linux, Windows, macOS

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21+ |
| Maven | 3.8+ |

---

## Build

```bash
# Clone / open the project
cd ping

# Compile and package fat JAR
mvn package

# Run directly
java -jar target/ping-1.0-SNAPSHOT.jar
```

---

## Build & Install as .deb (Linux)

```bash
# Fix output directory permissions (first time only)
sudo chown -R $USER:$USER output/

# Build the .deb
bash build-deb.sh

# Remove old version (if installed)
sudo dpkg -r ping

# Install new version
sudo dpkg -i output/ping-monitor_2.0_amd64.deb

# Launch
ping-monitor
```

---

## Server Configuration

### File location

| Context | Path |
|---|---|
| Development / IDE | `servers.conf` in project root |
| Installed `.deb` | `~/.config/ping-monitor/servers.conf` |

### Format

```
# Ping server list  —  format: ip=group
172.25.10.1=DNS Servers
172.26.10.2=DNS Servers
172.25.10.17=SVN Servers
172.25.10.2=Trunas Servers
172.25.10.115=IT-Windows
```

- One server per line: `ip=Group Name`
- Lines starting with `#` are comments and are ignored
- The file is created automatically with defaults on first launch
- You can edit it manually — changes take effect on next launch
- Adding or removing via the UI saves the file immediately

### Via the UI

- **Add:** Click **"+ Add Server"** at the bottom of the sidebar → enter IP and select/type a group name
- **Remove:** Right-click any server in the tree → **Remove Server**

---

## Project Structure

```
ping/
├── src/
│   └── main/
│       ├── java/ping/it/
│       │   └── ServerMonitoringUI.java   # Entire application
│       └── resources/
│           ├── ping.png                  # App icon (PNG)
│           └── ping.ico                  # App icon (Windows ICO)
├── output/                               # Built .deb packages
├── build-deb.sh                          # Linux .deb build script
├── ping.xml                              # Launch4j config (Windows .exe)
├── servers.conf                          # Server list (dev mode)
└── pom.xml
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| [FlatLaf](https://www.formdev.com/flatlaf/) | 3.6.2 | Dark theme UI |
| [JFreeChart](https://www.jfree.org/jfreechart/) | 1.5.6 | Time-series charts |
| [JNA](https://github.com/java-native-access/jna) | 5.13.0 | Windows taskbar integration |

---

## Windows (.exe)

Use [Launch4j](https://launch4j.sourceforge.net/) with the provided `ping.xml` config to wrap the fat JAR into a `.exe`.

```
ping.xml  →  Launch4j  →  ping.exe
```

---

## Uninstall (Linux)

```bash
# Remove package (keeps ~/.config/ping-monitor/servers.conf)
sudo dpkg -r ping-monitor

# Full removal including config
sudo dpkg --purge ping-monitor
rm -rf ~/.config/ping-monitor
```
