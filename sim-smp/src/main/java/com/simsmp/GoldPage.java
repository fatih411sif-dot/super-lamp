package com.simsmp;

/** The price page served by the bank web server (source: tools/web/gold.html). */
final class GoldPage {
   static final String HTML = """
<!doctype html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SIM SMP Gold Market</title>
<style>
:root{--bg:#0e1420;--panel:#162033;--line:#26344d;--text:#e8edf6;--mute:#8a98b3;--gold:#f0b429;--up:#3ddc84;--down:#ff5c6c}
*{box-sizing:border-box}
body{margin:0;background:radial-gradient(1200px 600px at 50% -200px,#1d2a44 0,var(--bg) 60%);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;min-height:100vh}
.wrap{max-width:880px;margin:0 auto;padding:20px 16px 40px}
header{display:flex;align-items:center;gap:12px;margin-bottom:18px}
.logo{width:40px;height:40px;border-radius:10px;background:linear-gradient(135deg,#ffd76a,#c98a0a);display:grid;place-items:center;color:#3a2600;font-weight:800}
h1{font-size:18px;margin:0}
.sub{color:var(--mute);font-size:13px}
.live{margin-left:auto;display:flex;align-items:center;gap:6px;color:var(--mute);font-size:12px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--up);box-shadow:0 0 8px var(--up)}
.dot.off{background:var(--down);box-shadow:0 0 8px var(--down)}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:18px}
.price{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}
.big{font-size:clamp(34px,8vw,52px);font-weight:800;color:var(--gold);letter-spacing:-.5px;font-variant-numeric:tabular-nums}
.unit{color:var(--mute);font-size:14px}
.chg{font-weight:700;font-size:16px;padding:3px 10px;border-radius:999px;background:#ffffff10}
.chg.up{color:var(--up);background:#3ddc8420}
.chg.down{color:var(--down);background:#ff5c6c20}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-top:14px}
.stat{background:#0f1827;border:1px solid var(--line);border-radius:10px;padding:10px 12px}
.stat b{display:block;font-size:18px;margin-top:2px;font-variant-numeric:tabular-nums}
.stat span{color:var(--mute);font-size:12px}
.chart{margin-top:14px;position:relative}
canvas{width:100%;height:280px;display:block}
#tip{position:absolute;pointer-events:none;background:#0b111c;border:1px solid var(--line);padding:6px 9px;border-radius:8px;font-size:12px;display:none;white-space:nowrap}
.bar{height:6px;background:#0f1827;border-radius:99px;overflow:hidden;margin-top:8px}
.bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,#c98a0a,#ffd76a);transition:width 1s linear}
.note{color:var(--mute);font-size:12px;margin-top:16px;line-height:1.5}
</style>
</head>
<body>
<div class="wrap">
<header>
<div class="logo">Au</div>
<div><h1>SIM SMP Gold Market</h1><div class="sub">Harga emas bank server, tanpa perlu masuk Minecraft</div></div>
<div class="live"><span class="dot" id="dot"></span><span id="status">memuat...</span></div>
</header>
<div class="card">
<div class="price"><span class="big" id="price">-</span><span class="unit">per ingot</span><span class="chg" id="chg">-</span></div>
<div class="grid">
<div class="stat"><span>Harga beli (kamu beli)</span><b id="buy">-</b></div>
<div class="stat"><span>Harga jual (kamu jual)</span><b id="sell">-</b></div>
<div class="stat"><span>Tertinggi (riwayat)</span><b id="hi">-</b></div>
<div class="stat"><span>Terendah (riwayat)</span><b id="lo">-</b></div>
<div class="stat"><span>Harga berubah dalam</span><b id="next">-</b><div class="bar"><i id="barfill"></i></div></div>
</div>
<div class="chart"><canvas id="cv"></canvas><div id="tip"></div></div>
<div class="note">Harga bergerak otomatis setiap beberapa menit (naik atau turun) dan kadang kena berita pasar. Beli di bank = harga beli, jual = harga jual. Halaman ini diperbarui otomatis.</div>
</div>
</div>
<script>
var cur="", hist=[], interval=300, left=0, ok=false, pts=[];
function fmt(n){return cur+Math.round(n).toLocaleString("id-ID")}
function el(i){return document.getElementById(i)}
function mmss(s){s=Math.max(0,Math.floor(s));var m=Math.floor(s/60),r=s%60;return m+":"+(r<10?"0":"")+r}
function load(){
  fetch("api/gold",{cache:"no-store"}).then(function(r){return r.json()}).then(function(d){
    ok=true;cur=d.currency||"";hist=d.history||[];interval=d.intervalSeconds||300;left=d.nextUpdateSeconds;
    el("price").textContent=fmt(d.price);
    var pct=d.prevPrice?(d.price-d.prevPrice)*100/d.prevPrice:0;
    var c=el("chg");c.textContent=(pct>=0?"+":"")+pct.toFixed(1)+"%";c.className="chg "+(pct>0?"up":pct<0?"down":"");
    el("buy").textContent=fmt(d.buyPrice);el("sell").textContent=fmt(d.sellPrice);
    var hi=Math.max.apply(null,hist),lo=Math.min.apply(null,hist);
    el("hi").textContent=fmt(hi);el("lo").textContent=fmt(lo);
    el("dot").className="dot";el("status").textContent="langsung";
    draw();
  }).catch(function(){ok=false;el("dot").className="dot off";el("status").textContent="server tidak terjangkau"});
}
function tick(){
  if(!ok)return;
  left=Math.max(0,left-1);
  el("next").textContent=mmss(left);
  el("barfill").style.width=(100-Math.min(100,left*100/interval))+"%";
  if(left===0)setTimeout(load,1500);
}
function draw(){
  var cv=el("cv"),dpr=window.devicePixelRatio||1,w=cv.clientWidth,h=cv.clientHeight;
  cv.width=w*dpr;cv.height=h*dpr;var x=cv.getContext("2d");x.scale(dpr,dpr);x.clearRect(0,0,w,h);
  if(hist.length<2){x.fillStyle="#8a98b3";x.font="13px sans-serif";x.fillText("Riwayat harga belum cukup, tunggu perubahan berikutnya.",12,24);pts=[];return}
  var hi=Math.max.apply(null,hist),lo=Math.min.apply(null,hist);
  var pad=(hi-lo)*0.15||hi*0.05;hi+=pad;lo-=pad;
  var L=56,R=12,T=14,B=26,pw=w-L-R,ph=h-T-B;
  x.strokeStyle="#26344d";x.fillStyle="#8a98b3";x.font="11px sans-serif";x.lineWidth=1;
  for(var g=0;g<=4;g++){var gy=T+ph*g/4;x.beginPath();x.moveTo(L,gy);x.lineTo(w-R,gy);x.stroke();x.fillText(fmt(hi-(hi-lo)*g/4),4,gy+4)}
  pts=[];
  for(var i=0;i<hist.length;i++){pts.push([L+pw*i/(hist.length-1),T+ph*(1-(hist[i]-lo)/(hi-lo))])}
  var gr=x.createLinearGradient(0,T,0,T+ph);gr.addColorStop(0,"rgba(240,180,41,.35)");gr.addColorStop(1,"rgba(240,180,41,0)");
  x.beginPath();x.moveTo(pts[0][0],T+ph);for(i=0;i<pts.length;i++)x.lineTo(pts[i][0],pts[i][1]);x.lineTo(pts[pts.length-1][0],T+ph);x.closePath();x.fillStyle=gr;x.fill();
  x.beginPath();for(i=0;i<pts.length;i++){if(i===0)x.moveTo(pts[i][0],pts[i][1]);else x.lineTo(pts[i][0],pts[i][1])}
  x.strokeStyle="#f0b429";x.lineWidth=2.5;x.lineJoin="round";x.stroke();
  var last=pts[pts.length-1];x.beginPath();x.arc(last[0],last[1],5,0,7);x.fillStyle="#ffd76a";x.fill();
  x.fillStyle="#8a98b3";x.textAlign="right";x.fillText("sekarang",w-R,h-8);x.textAlign="left";
  var mins=Math.round((hist.length-1)*interval/60);x.fillText(mins+" menit lalu",L,h-8);
}
var cvEl=el("cv");
cvEl.addEventListener("mousemove",function(e){
  if(!pts.length)return;var r=cvEl.getBoundingClientRect(),mx=e.clientX-r.left,b=0,bd=1e9;
  for(var i=0;i<pts.length;i++){var dd=Math.abs(pts[i][0]-mx);if(dd<bd){bd=dd;b=i}}
  var t=el("tip");t.style.display="block";t.style.left=Math.min(r.width-130,Math.max(0,pts[b][0]-50))+"px";t.style.top=Math.max(0,pts[b][1]-44)+"px";
  var ago=Math.round((hist.length-1-b)*interval/60);t.textContent=fmt(hist[b])+" - "+(ago===0?"sekarang":ago+" menit lalu");
});
cvEl.addEventListener("mouseleave",function(){el("tip").style.display="none"});
window.addEventListener("resize",draw);
load();setInterval(load,15000);setInterval(tick,1000);
</script>
</body>
</html>
""";

   private GoldPage() {
   }
}
