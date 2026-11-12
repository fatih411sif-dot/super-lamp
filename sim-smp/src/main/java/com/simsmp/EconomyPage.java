package com.simsmp;

/** Economy page (source: tools/web/economy.html). */
final class EconomyPage {
   static final String HTML = """
<!doctype html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SIM SMP Economy</title>
<style>
:root{--bg:#0e1420;--panel:#162033;--line:#26344d;--text:#e8edf6;--mute:#8a98b3;--gold:#f0b429;--up:#3ddc84;--down:#ff5c6c}
*{box-sizing:border-box}
body{margin:0;background:radial-gradient(1200px 600px at 50% -200px,#1d2a44 0,var(--bg) 60%);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;min-height:100vh}
.wrap{max-width:920px;margin:0 auto;padding:20px 16px 40px}
header{display:flex;align-items:center;gap:12px;margin-bottom:18px;flex-wrap:wrap}
h1{font-size:18px;margin:0}
.sub{color:var(--mute);font-size:13px}
a{color:var(--gold)}
.live{margin-left:auto;color:var(--mute);font-size:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:14px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}
.lbl{color:var(--mute);font-size:12px;text-transform:uppercase;letter-spacing:.05em}
.big{font-size:32px;font-weight:800;margin-top:4px}
.up{color:var(--up)}.down{color:var(--down)}.gold{color:var(--gold)}
svg{width:100%;height:200px;display:block}
table{width:100%;border-collapse:collapse;font-size:14px}
th,td{padding:8px 6px;text-align:right;border-bottom:1px solid var(--line)}
th:first-child,td:first-child{text-align:left}
th{color:var(--mute);font-weight:600;font-size:12px}
.tag{font-size:11px;padding:2px 7px;border-radius:99px;background:#22314d}
@media(max-width:560px){.hide{display:none}}
</style>
</head>
<body>
<div class="wrap">
<header><div><h1>SIM SMP Economy</h1><div class="sub">Inflasi, harga ore dan stok toko /buy &middot; <a href="/">Harga emas</a></div></div><div class="live" id="live">memuat...</div></header>
<div class="grid">
<div class="card"><div class="lbl">Indeks mata uang</div><div class="big" id="idx">-</div><div class="sub" id="state">-</div></div>
<div class="card"><div class="lbl">Harga emas / ingot</div><div class="big gold" id="gold">-</div><div class="sub" id="goldsub">-</div></div>
<div class="card"><div class="lbl">Volume jual-beli</div><div class="big" id="vol">-</div><div class="sub" id="voltarget">-</div></div>
<div class="card"><div class="lbl">Update berikutnya</div><div class="big" id="next">-</div><div class="sub">tiap 5 menit</div></div>
</div>
<div class="card" style="margin-bottom:14px"><div class="lbl">Riwayat indeks</div><svg id="chart" viewBox="0 0 600 200" preserveAspectRatio="none"></svg></div>
<div class="card"><div class="lbl" style="margin-bottom:8px">Harga ore dan ingot</div>
<table><thead><tr><th>Item</th><th>Sistem beli</th><th>/buy</th><th class="hide">Multiplier</th><th>Stok</th></tr></thead><tbody id="rows"></tbody></table>
<div class="sub" style="margin-top:10px">Banyak pemain jual ke sistem = harga beli sistem turun (jual ke pemain lain!). Sedikit yang jual = harga naik sampai x1.5.</div></div>
</div>
<script>
function fmt(n){return Number(n).toLocaleString('id-ID')}
function load(){
fetch('/api/economy',{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){
document.getElementById('live').textContent='live '+new Date().toLocaleTimeString();
var i=d.inflation;
document.getElementById('idx').textContent=i.toFixed(2);
var st=i>1.05?'Inflasi':(i<0.95?'Deflasi':'Stabil');
var ch=d.lastChangePct;
document.getElementById('state').innerHTML=st+' <span class="'+(ch>=0?'up':'down')+'">'+(ch>=0?'+':'')+ch.toFixed(1)+'%</span>';
document.getElementById('gold').textContent=fmt(d.goldPrice);
document.getElementById('goldsub').textContent='beli '+fmt(d.goldBuy)+' / jual '+fmt(d.goldSell);
document.getElementById('vol').textContent=fmt(d.volume);
document.getElementById('voltarget').textContent='target '+fmt(d.targetVolume);
var s=d.secondsToUpdate;document.getElementById('next').textContent=Math.floor(s/60)+':'+('0'+(s%60)).slice(-2);
var h=d.history;
if(h.length>1){
var mn=Math.min.apply(null,h),mx=Math.max.apply(null,h);if(mx-mn<0.02){mx+=0.01;mn-=0.01}
var pts=h.map(function(v,k){return (k*600/(h.length-1)).toFixed(1)+','+(190-(v-mn)/(mx-mn)*180).toFixed(1)}).join(' ');
document.getElementById('chart').innerHTML='<polyline fill="none" stroke="#f0b429" stroke-width="2" points="'+pts+'"/>';
}
var rows='';
d.items.forEach(function(it){
rows+='<tr><td>'+it.name+'</td><td>'+fmt(it.sell)+'</td><td>'+(it.stock>0?fmt(it.buy):'-')+'</td><td class="hide"><span class="tag">x'+it.mult.toFixed(2)+'</span></td><td>'+fmt(it.stock)+'</td></tr>';
});
document.getElementById('rows').innerHTML=rows||'<tr><td colspan="5">Belum ada data</td></tr>';
}).catch(function(){document.getElementById('live').textContent='offline'});
}
load();setInterval(load,5000);
</script>
</body>
</html>
""";
}
