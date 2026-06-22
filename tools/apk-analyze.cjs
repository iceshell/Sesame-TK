const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');

const apkPath = 'd:/Sesame-TK/Sesame-TK/app/build/outputs/apk/release/Sesame-TK-arm64-v8a-0.9.9.apk';
const zip = new AdmZip(apkPath);
const entries = zip.getEntries();

// Analyze assets specifically
let assets = [];
let modelFiles = [];
let otherLarge = [];

for (const entry of entries) {
    const name = entry.entryName;
    const size = entry.header.size;
    
    if (name.startsWith('assets/')) {
        assets.push({name: name.replace('assets/', ''), size});
    }
}

assets.sort((a,b) => b.size - a.size);

console.log('=== Assets Breakdown ===');
console.log('Total assets:', assets.length, 'files,', (assets.reduce((s,e) => s+e.size,0)/1024/1024).toFixed(1), 'MB');
console.log('');

let totalShown = 0;
for (const a of assets) {
    const mb = a.size / 1024 / 1024;
    console.log((mb >= 0.1 ? '⬜ ' : '   ') + (mb >= 1 ? mb.toFixed(1) + 'MB' : (a.size/1024).toFixed(0) + 'KB').padStart(8) + '  ' + a.name);
    totalShown += a.size;
    if (totalShown > 50 * 1024 * 1024) break; // Limit output
}
