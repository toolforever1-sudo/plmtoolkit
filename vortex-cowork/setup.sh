#!/bin/bash
set -e

echo ""
echo "  ⚡ Vortex Cowork — Setup"
echo "  ─────────────────────────────"
echo ""

# Check Node
if ! command -v node &> /dev/null; then
  echo "  ✗ Node.js not found."
  echo "    Install it from https://nodejs.org (LTS version) then re-run this script."
  exit 1
fi
echo "  ✓ Node $(node -v) found"

# Install dependencies
echo "  → Installing dependencies..."
npm install --silent

# Explicitly install Electron binary (fixes the 'Electron uninstalled' error)
echo "  → Installing Electron binary..."
node node_modules/electron/install.js

# Set up .env if it doesn't exist
if [ ! -f .env ]; then
  cp .env.example .env
  echo ""
  echo "  ─────────────────────────────"
  echo "  Almost there! Open .env and add your API key:"
  echo ""
  echo "    PORTKEY_KEY=your_key_here"
  echo ""
  echo "  Then run:  npm run dev"
  echo "  ─────────────────────────────"
else
  echo "  ✓ .env already exists"
  echo ""
  echo "  ─────────────────────────────"
  echo "  Ready! Run:  npm run dev"
  echo "  ─────────────────────────────"
fi

# Offer to add the vortex alias
INSTALL_DIR="$(pwd)"
ALIAS_LINE="alias vortex=\"cd $INSTALL_DIR && npm run dev\""

if ! grep -q "alias vortex=" ~/.zshrc 2>/dev/null; then
  echo ""
  read -p "  Add 'vortex' shortcut command to your terminal? (y/n) " -n 1 -r
  echo ""
  if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "$ALIAS_LINE" >> ~/.zshrc
    source ~/.zshrc 2>/dev/null || true
    echo "  ✓ Done — just type 'vortex' anywhere to launch the app"
  fi
fi

echo ""
