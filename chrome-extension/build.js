/**
 * Build Script for RamaPay Chrome Extension
 * Bundles dependencies and prepares for distribution
 */

import * as esbuild from 'esbuild';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const isWatch = process.argv.includes('--watch');

async function build() {
  console.log('🔨 Building RamaPay Chrome Extension...\n');

  // Ensure dist directory exists
  const distDir = path.join(__dirname, 'dist');
  if (!fs.existsSync(distDir)) {
    fs.mkdirSync(distDir, { recursive: true });
  }

  try {
    // Bundle the wallet library
    await esbuild.build({
      entryPoints: ['lib/wallet.js'],
      bundle: true,
      outfile: 'dist/lib/wallet.bundle.js',
      format: 'esm',
      platform: 'browser',
      target: 'chrome100',
      minify: !isWatch,
      sourcemap: isWatch,
      external: ['chrome']
    });
    console.log('✅ Bundled wallet library');

    // Bundle the service worker
    await esbuild.build({
      entryPoints: ['background/service-worker.js'],
      bundle: true,
      outfile: 'dist/background/service-worker.js',
      format: 'esm',
      platform: 'browser',
      target: 'chrome100',
      minify: !isWatch,
      sourcemap: isWatch,
      external: ['chrome']
    });
    console.log('✅ Bundled service worker');

    // Copy static files
    const filesToCopy = [
      'manifest.json',
      'popup/popup.html',
      'popup/popup.css',
      'popup/popup.js',
      'content/inject.js',
      'inpage/provider.js'
    ];

    for (const file of filesToCopy) {
      const src = path.join(__dirname, file);
      const dest = path.join(distDir, file);
      const destDir = path.dirname(dest);
      
      if (!fs.existsSync(destDir)) {
        fs.mkdirSync(destDir, { recursive: true });
      }
      
      if (fs.existsSync(src)) {
        fs.copyFileSync(src, dest);
        console.log(`✅ Copied ${file}`);
      }
    }

    // Copy icons
    const iconsDir = path.join(__dirname, 'icons');
    const distIconsDir = path.join(distDir, 'icons');
    if (!fs.existsSync(distIconsDir)) {
      fs.mkdirSync(distIconsDir, { recursive: true });
    }
    
    if (fs.existsSync(iconsDir)) {
      const iconFiles = fs.readdirSync(iconsDir);
      for (const icon of iconFiles) {
        fs.copyFileSync(
          path.join(iconsDir, icon),
          path.join(distIconsDir, icon)
        );
      }
      console.log('✅ Copied icons');
    }

    console.log('\n🎉 Build complete!');
    console.log(`📁 Output: ${distDir}`);
    
    if (isWatch) {
      console.log('\n👀 Watching for changes...');
    }

  } catch (error) {
    console.error('❌ Build failed:', error);
    process.exit(1);
  }
}

build();

if (isWatch) {
  // Simple file watcher
  const watchDirs = ['lib', 'background', 'popup', 'content', 'inpage'];
  
  watchDirs.forEach(dir => {
    const dirPath = path.join(__dirname, dir);
    if (fs.existsSync(dirPath)) {
      fs.watch(dirPath, { recursive: true }, (eventType, filename) => {
        console.log(`\n📝 Changed: ${dir}/${filename}`);
        build();
      });
    }
  });
}
