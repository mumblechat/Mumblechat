#!/bin/bash
# MumbleChat Relay Desktop App - Build Script
# Run this on macOS to build DMG, or on any platform for local development

set -e

echo "🚀 MumbleChat Relay Desktop Build Script"
echo "========================================"

# Check Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js not found. Please install Node.js 18+ from https://nodejs.org"
    exit 1
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "❌ Node.js 18+ required. Current: $(node -v)"
    exit 1
fi

echo "✅ Node.js $(node -v) detected"

# Install dependencies
echo ""
echo "📦 Installing dependencies..."
npm install

# Check platform
PLATFORM=$(uname -s)

case "$PLATFORM" in
    Darwin)
        echo ""
        echo "🍎 macOS detected - Building DMG..."
        npm run build:mac
        echo ""
        echo "✅ Build complete! Find your DMG in ./dist/"
        open ./dist 2>/dev/null || true
        ;;
    Linux)
        echo ""
        echo "🐧 Linux detected - Building AppImage..."
        npm run build:linux
        echo ""
        echo "✅ Build complete! Find your AppImage in ./dist/"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        echo ""
        echo "🪟 Windows detected - Building installer..."
        npm run build:win
        echo ""
        echo "✅ Build complete! Find your installer in ./dist/"
        ;;
    *)
        echo ""
        echo "❓ Unknown platform: $PLATFORM"
        echo "Running in development mode instead..."
        npm start
        ;;
esac

echo ""
echo "📡 MumbleChat Relay Desktop App"
echo "   Version: 4.0.0"
echo "   Hub: wss://hub.mumblechat.com/node/connect"
echo ""
echo "For development: npm start"
echo "For production build: npm run build"
