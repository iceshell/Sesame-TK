const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const htmlPath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web', 'semi_index.html');
const jsxOutput = path.join(__dirname, 'semi_app.jsx');
const jsOutput = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web', 'js', 'semi-app.js');
const babelCli = path.join(__dirname, 'build-cache', 'node_modules', '.bin', 'babel');

// 1. 从HTML提取JSX代码块
const html = fs.readFileSync(htmlPath, 'utf-8');
const jsxMatch = html.match(/<script type="text\/babel">([\s\S]*?)<\/script>/);
if (!jsxMatch) { console.log('No Babel JSX block found in HTML'); process.exit(1); }

const jsxCode = jsxMatch[1].trim();
fs.writeFileSync(jsxOutput, jsxCode, 'utf-8');
console.log('Extracted JSX:', jsxCode.length, 'chars');

// 2. Babel编译（classic runtime → React.createElement）
// 使用--config-file指定babel配置位置（node_modules在build-cache中）
const configFile = path.join(__dirname, 'build-cache', 'babel.config.json');
fs.writeFileSync(configFile, JSON.stringify({
  presets: [["@babel/preset-react", { runtime: "classic" }]]
}));
const buildCacheDir = path.join(__dirname, 'build-cache');
try {
  execSync(`"${babelCli}" "${jsxOutput}" -o "${jsOutput}" --config-file "${configFile}"`, { stdio: 'inherit', cwd: buildCacheDir });
  const result = fs.readFileSync(jsOutput, 'utf-8');
  const hasImport = result.includes('import {');
  console.log('Compiled JS:', result.length, 'chars, has ESM import:', hasImport);
  
  if (hasImport) {
    console.log('WARNING: ESM import detected - classic runtime may have failed!');
  } else {
    console.log('SUCCESS: Classic runtime OK, using React.createElement');
  }
} catch(e) {
  console.error('Babel compile failed:', e.message);
  process.exit(1);
}

// 3. 更新HTML：把 <script type="text/babel"> 块替换为 <script src>
const updatedHtml = html.replace(
  /<script type="text\/babel">[\s\S]*?<\/script>/,
  '<script src="./js/semi-app.js"></script>'
);

// 4. 同时删除 babel.min.js 引用
const updatedHtml2 = updatedHtml.replace(
  /<!-- Babel -->\s*<script src="\.\/js\/babel\.min\.js"><\/script>/,
  '<!-- Pre-compiled App (JSX compiled offline, no runtime Babel needed) -->'
);

fs.writeFileSync(htmlPath, updatedHtml2, 'utf-8');
console.log('HTML updated, babel.min.js reference removed');
