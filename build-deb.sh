#!/bin/bash
set -e

APP="ping-monitor"
VERSION="2.0"
ARCH="amd64"
DEB_DIR="output/${APP}_${VERSION}_${ARCH}"
OUT="output/${APP}_${VERSION}_${ARCH}.deb"

echo "==> Building fat JAR..."
mvn package -q

echo "==> Staging .deb structure..."
rm -rf "${DEB_DIR}"

# Directories
mkdir -p "${DEB_DIR}/DEBIAN"
mkdir -p "${DEB_DIR}/usr/share/${APP}"
mkdir -p "${DEB_DIR}/usr/local/bin"
mkdir -p "${DEB_DIR}/usr/share/applications"
mkdir -p "${DEB_DIR}/usr/share/icons/hicolor/48x48/apps"

# JAR
cp target/ping-1.0-SNAPSHOT.jar "${DEB_DIR}/usr/share/${APP}/ping.jar"

# Icon
cp src/main/resources/ping.png "${DEB_DIR}/usr/share/${APP}/ping.png"
cp src/main/resources/ping.png "${DEB_DIR}/usr/share/icons/hicolor/48x48/apps/${APP}.png"

# Launcher script
cat > "${DEB_DIR}/usr/local/bin/${APP}" <<'LAUNCHER'
#!/bin/bash
exec java -jar /usr/share/ping-monitor/ping.jar "$@"
LAUNCHER
chmod 755 "${DEB_DIR}/usr/local/bin/${APP}"

# Desktop entry
cat > "${DEB_DIR}/usr/share/applications/${APP}.desktop" <<DESKTOP
[Desktop Entry]
Name=Ping Monitor
Comment=Server Network Monitoring Tool
Exec=/usr/local/bin/${APP}
Icon=${APP}
Terminal=false
Type=Application
Categories=Network;Monitor;System;
StartupNotify=true
DESKTOP

# DEBIAN/control
cat > "${DEB_DIR}/DEBIAN/control" <<CONTROL
Package: ${APP}
Version: ${VERSION}
Architecture: ${ARCH}
Maintainer: Dhinesh P <kuberan.k@knowledgeq.com>
Depends: default-jre | openjdk-21-jre
Description: Ping Monitor - Server Network Monitoring Tool
 A desktop GUI application that monitors server availability via ICMP ping.
 Displays real-time up/down status charts for each server with live updates.
 Server list is stored in ~/.config/ping-monitor/servers.conf.
CONTROL

# Fix permissions
find "${DEB_DIR}" -type d -exec chmod 0755 {} \;
find "${DEB_DIR}" -type f -exec chmod 0644 {} \;
chmod 0755 "${DEB_DIR}/usr/local/bin/${APP}"
chmod 0755 "${DEB_DIR}/DEBIAN"

echo "==> Building .deb package..."
fakeroot dpkg-deb --build "${DEB_DIR}" "${OUT}"

echo ""
echo "Done: ${OUT}"
echo "Install with:  sudo dpkg -i ${OUT}"
echo "Run with:      ping-monitor"
