const fs = require('fs');
const c = fs.readFileSync('D:/Sesame-TK/Sesame-TK/log/record.log', 'utf-8');
const lines = c.split('\n');

// Find auto-slide attempts (distinct from manual)
const autoSlides = [];
lines.forEach(function(l) {
    if (l.includes('执行滑动') || l.includes('距离=0px') || l.includes('FAILED_RETRYABLE') || 
        l.includes('HANDLED') || l.includes('diffRatio') || l.includes('校正滑动')) {
        autoSlides.push(l);
    }
});

console.log('=== Auto-slide events:', autoSlides.length, '===');
autoSlides.slice(-50).forEach(function(l) { console.log(l.substring(0,300)); });

// Show all auto-slide attempts with distance=0
console.log('\n=== Distance=0 slides (no movement) ===');
lines.forEach(function(l, i) {
    if (l.includes('实际滑动参数') && l.includes('距离=0') && !l.includes('距离=0px')) {
        console.log('L' + i + ': ' + l.substring(0,250));
    }
});
