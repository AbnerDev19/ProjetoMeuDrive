package com.meudrive

val WEB_PAGE = """
<!doctype html>
<html lang="pt-br">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Meu Drive</title>
<style>
:root{--bg:#fff;--side:#f7f7f5;--text:#37352f;--muted:#787774;--line:#e9e9e7;--hover:#efefed;--accent:#2383e2;--shadow:0 2px 10px rgba(0,0,0,.12)}
@media(prefers-color-scheme:dark){:root{--bg:#191919;--side:#202020;--text:#e6e6e4;--muted:#9b9b9b;--line:#303030;--hover:#2b2b2b;--shadow:0 2px 12px rgba(0,0,0,.55)}}
*{box-sizing:border-box}
html,body{height:100%}
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--text);font-size:14px}
button{font:inherit;color:inherit;cursor:pointer}
[hidden]{display:none!important}
svg{fill:currentColor}
#login{display:flex;align-items:center;justify-content:center;height:100%;background:var(--side)}
.lbox{width:340px;max-width:92%;background:var(--bg);border:1px solid var(--line);border-radius:14px;padding:28px;box-shadow:var(--shadow)}
.lbox h2{margin:0 0 4px;font-size:22px}
.lbox p{margin:0 0 18px;color:var(--muted)}
.lbox input{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--text);font:inherit;margin-bottom:12px;outline:none}
.lbox input:focus{border-color:var(--accent)}
.btn{background:var(--accent);color:#fff;border:0;border-radius:8px;padding:10px 16px;width:100%;font-weight:500}
#err{color:#d93025;margin:10px 0 0;min-height:18px}
.app{display:grid;grid-template-columns:240px 1fr;height:100%}
.side{background:var(--side);border-right:1px solid var(--line);padding:16px 12px;display:flex;flex-direction:column;gap:16px}
.brand{display:flex;align-items:center;gap:10px;font-size:18px;font-weight:600;padding:4px 8px}
.logo{width:28px;height:28px;border-radius:8px;background:var(--accent);display:grid;place-items:center;color:#fff}
.logo svg{width:17px;height:17px}
.newwrap{position:relative}
.newbtn{display:flex;align-items:center;gap:10px;background:var(--bg);border:1px solid var(--line);border-radius:16px;padding:12px 22px;box-shadow:var(--shadow);font-weight:500}
.newbtn svg{width:20px;height:20px;color:var(--accent)}
.menu{position:absolute;top:100%;left:0;margin-top:6px;min-width:220px;background:var(--bg);border:1px solid var(--line);border-radius:10px;box-shadow:var(--shadow);padding:6px;z-index:20}
.menu button{display:flex;gap:12px;align-items:center;width:100%;background:none;border:0;padding:10px;border-radius:6px;text-align:left}
.menu button:hover,.nav a:hover{background:var(--hover)}
.menu svg{width:18px;height:18px;color:var(--muted)}
.nav a{display:flex;gap:12px;align-items:center;padding:8px 10px;border-radius:6px;cursor:pointer;color:var(--muted)}
.nav a.on{background:var(--hover);color:var(--text);font-weight:500}
.nav svg{width:18px;height:18px}
.main{display:flex;flex-direction:column;min-width:0;height:100%}
.top{display:flex;gap:12px;align-items:center;padding:14px 24px}
.search{flex:1;max-width:620px;display:flex;align-items:center;gap:10px;background:var(--side);border-radius:24px;padding:0 16px;color:var(--muted)}
.search svg{width:18px;height:18px}
.search input{flex:1;border:0;background:none;outline:none;padding:12px 0;color:var(--text);font:inherit}
.seg{display:flex;border:1px solid var(--line);border-radius:8px;overflow:hidden;margin-left:auto}
.seg button{background:none;border:0;padding:7px 10px;display:flex;color:var(--muted)}
.seg button.on{background:var(--hover);color:var(--text)}
.seg svg{width:18px;height:18px}
.crumbs{padding:4px 24px 12px;font-size:18px;display:flex;flex-wrap:wrap;gap:4px;align-items:center}
.crumbs a{padding:4px 8px;border-radius:6px;cursor:pointer}
.crumbs a:hover{background:var(--hover)}
.crumbs span{color:var(--muted)}
.content{flex:1;overflow:auto;padding:0 24px 80px}
.head,.row{display:grid;grid-template-columns:1fr 110px 130px 84px;align-items:center;gap:12px;padding:0 12px}
.head{color:var(--muted);font-size:12px;border-bottom:1px solid var(--line);height:36px;position:sticky;top:0;background:var(--bg);z-index:2}
.row{height:50px;border-bottom:1px solid var(--line);cursor:pointer}
.row:hover{background:var(--hover)}
.nm{display:flex;gap:12px;align-items:center;min-width:0}
.nm b{font-weight:400;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.muted{color:var(--muted)}
.ic{width:28px;height:28px;border-radius:6px;display:grid;place-items:center;color:#fff;font-size:9px;font-weight:700;flex:none}
.ic svg{width:28px;height:28px;color:#8c8f94}
.acts{display:flex;justify-content:flex-end;opacity:0}
.row:hover .acts,.card:hover .acts{opacity:1}
.ib{background:none;border:0;border-radius:50%;width:32px;height:32px;display:grid;place-items:center;color:var(--muted)}
.ib:hover{background:var(--line)}
.ib svg{width:18px;height:18px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:14px;padding-top:4px}
.card{border:1px solid var(--line);border-radius:12px;overflow:hidden;cursor:pointer;position:relative;background:var(--bg)}
.card:hover{background:var(--hover)}
.thumb{height:130px;background:var(--side);display:grid;place-items:center;overflow:hidden}
.thumb img{width:100%;height:100%;object-fit:cover}
.thumb .ic{width:56px;height:56px;font-size:14px;border-radius:12px}
.thumb .ic svg{width:56px;height:56px}
.cf{padding:10px 12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.card .acts{position:absolute;top:6px;right:6px;background:var(--bg);border-radius:20px;box-shadow:var(--shadow)}
.empty{text-align:center;color:var(--muted);padding:80px 0;line-height:1.7}
.toast{position:fixed;right:24px;bottom:24px;width:320px;background:var(--text);color:var(--bg);border-radius:10px;padding:14px 16px;box-shadow:var(--shadow);z-index:30}
.tb{height:4px;background:rgba(128,128,128,.4);border-radius:2px;margin-top:10px}
#tbar{height:100%;width:0;background:var(--accent);border-radius:2px}
.drag .content{outline:2px dashed var(--accent);outline-offset:-8px;background:var(--hover)}
@media(max-width:720px){
.app{grid-template-columns:1fr;grid-template-rows:auto 1fr}
.side{flex-direction:row;align-items:center;justify-content:space-between;border-right:0;border-bottom:1px solid var(--line);padding:10px 14px}
.nav{display:none}
.menu{left:auto;right:0}
.top{padding:10px 14px}
.crumbs,.content{padding-left:14px;padding-right:14px}
.head,.row{grid-template-columns:1fr 84px}
.c2,.c3{display:none}
.acts{opacity:1}
.seg{display:none}
.toast{left:14px;right:14px;width:auto}
}
</style>
</head>
<body>
<div id="login" hidden>
  <div class="lbox">
    <h2>Meu Drive</h2>
    <p>Digite a senha para entrar.</p>
    <input id="pw" type="password" placeholder="Senha" onkeydown="if(event.key==='Enter')login()">
    <button class="btn" onclick="login()">Entrar</button>
    <div id="err"></div>
  </div>
</div>

<div class="app" id="app" hidden>
  <aside class="side">
    <div class="brand"><div class="logo"><svg viewBox="0 0 24 24"><path d="M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z"/></svg></div>Meu Drive</div>
    <div class="newwrap">
      <button class="newbtn" onclick="toggleMenu(event)"><svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>Novo</button>
      <div class="menu" id="menu" hidden>
        <button onclick="g('f').click()"><svg viewBox="0 0 24 24"><path d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z"/></svg>Enviar arquivos</button>
        <button onclick="mkdir()"><svg viewBox="0 0 24 24"><path d="M20 6h-8l-2-2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2zm-1 8h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z"/></svg>Nova pasta</button>
      </div>
    </div>
    <nav class="nav"><a class="on" onclick="load('')"><svg viewBox="0 0 24 24"><path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>Meus arquivos</a></nav>
  </aside>

  <section class="main">
    <div class="top">
      <div class="search"><svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27A6.5 6.5 0 1 0 14 15.5l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.49 4.49 0 0 1 9.5 14z"/></svg><input id="q" placeholder="Pesquisar nesta pasta" oninput="q=this.value.toLowerCase();draw()"></div>
      <div class="seg">
        <button id="vl" title="Lista" onclick="setView('list')"><svg viewBox="0 0 24 24"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg></button>
        <button id="vg" title="Grade" onclick="setView('grid')"><svg viewBox="0 0 24 24"><path d="M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z"/></svg></button>
      </div>
    </div>
    <div class="crumbs" id="crumbs"></div>
    <div class="content">
      <div id="files"></div>
      <div class="empty" id="empty" hidden>Nada por aqui<br>Arraste arquivos para cá ou use o botão "Novo"</div>
    </div>
  </section>
</div>

<input type="file" id="f" multiple hidden onchange="upload(this.files)">
<div class="toast" id="toast" hidden><div id="ttxt"></div><div class="tb"><div id="tbar"></div></div></div>

<script>
function g(x){ return document.getElementById(x); }
var cur = '', items = [], view = 'list', q = '';
try { view = localStorage.getItem('view') || 'list'; } catch (e) {}

var FOLDER = '<svg viewBox="0 0 24 24"><path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>';
var I_DL = '<svg viewBox="0 0 24 24"><path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/></svg>';
var I_DEL = '<svg viewBox="0 0 24 24"><path d="M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>';
var COL = {img:'#34a853', vid:'#ea4335', pdf:'#d93025', aud:'#9334e6', zip:'#f29900', doc:'#4285f4', def:'#80868b'};

function show(ok){ g('login').hidden = ok; g('app').hidden = !ok; }
function join(a, b){ return a ? a + '/' + b : b; }
function sz(n){
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}
function dt(t){ return new Date(t).toLocaleDateString('pt-BR', {day:'2-digit', month:'short', year:'numeric'}); }
function ext(n){ var i = n.lastIndexOf('.'); return i < 0 ? '' : n.slice(i + 1).toLowerCase(); }
function has(list, e){ return list.split(',').indexOf(e) >= 0; }
function kind(n){
  var e = ext(n);
  if (has('jpg,jpeg,png,gif,webp,bmp,svg', e)) return 'img';
  if (has('mp4,mkv,mov,avi,webm,3gp', e)) return 'vid';
  if (e === 'pdf') return 'pdf';
  if (has('mp3,wav,ogg,m4a,flac,aac', e)) return 'aud';
  if (has('zip,rar,7z,tar,gz', e)) return 'zip';
  if (has('doc,docx,txt,odt,xls,xlsx,ppt,pptx,csv', e)) return 'doc';
  return 'def';
}
function icon(it){
  var d = document.createElement('div');
  d.className = 'ic';
  if (it.dir){ d.innerHTML = FOLDER; return d; }
  d.style.background = COL[kind(it.name)];
  d.textContent = (ext(it.name) || 'arq').slice(0, 4).toUpperCase();
  return d;
}

function login(){
  var b = new URLSearchParams();
  b.set('password', g('pw').value);
  fetch('/api/login', {method:'POST', body:b}).then(function(r){
    if (r.ok){ g('err').textContent = ''; load(''); }
    else g('err').textContent = 'Senha incorreta';
  });
}

function load(p){
  cur = p; q = ''; g('q').value = ''; g('menu').hidden = true;
  fetch('/api/list?path=' + encodeURIComponent(p)).then(function(r){
    if (r.status === 401){ show(false); return null; }
    return r.json();
  }).then(function(d){
    if (!d) return;
    show(true); items = d.items; crumbs(); draw();
  });
}

function crumb(label, path, last){
  var a = document.createElement('a');
  a.textContent = label;
  if (last) a.style.fontWeight = '600';
  a.onclick = function(){ load(path); };
  return a;
}
function crumbs(){
  var c = g('crumbs'); c.textContent = '';
  var parts = cur.split('/').filter(Boolean), acc = '';
  c.appendChild(crumb('Meu Drive', '', parts.length === 0));
  parts.forEach(function(s, i){
    acc = join(acc, s);
    var sp = document.createElement('span'); sp.textContent = '›';
    c.appendChild(sp);
    c.appendChild(crumb(s, acc, i === parts.length - 1));
  });
}

function btn(svg, t, fn){
  var b = document.createElement('button');
  b.className = 'ib'; b.title = t; b.innerHTML = svg;
  b.onclick = function(e){ e.stopPropagation(); fn(); };
  return b;
}
function dl(p){ location.href = '/api/download?path=' + encodeURIComponent(p); }
function openItem(it, p){ if (it.dir) load(p); else dl(p); }
function acts(it, p){
  var a = document.createElement('div'); a.className = 'acts';
  if (!it.dir) a.appendChild(btn(I_DL, 'Baixar', function(){ dl(p); }));
  a.appendChild(btn(I_DEL, 'Apagar', function(){
    if (confirm('Apagar "' + it.name + '"?')) post('/api/delete', {path: p});
  }));
  return a;
}

function row(it){
  var p = join(cur, it.name);
  var r = document.createElement('div'); r.className = 'row';
  r.onclick = function(){ openItem(it, p); };
  var n = document.createElement('div'); n.className = 'nm';
  n.appendChild(icon(it));
  var b = document.createElement('b'); b.textContent = it.name; n.appendChild(b);
  var s = document.createElement('div'); s.className = 'muted c2'; s.textContent = it.dir ? '—' : sz(it.size);
  var d = document.createElement('div'); d.className = 'muted c3'; d.textContent = dt(it.mtime);
  r.appendChild(n); r.appendChild(s); r.appendChild(d); r.appendChild(acts(it, p));
  return r;
}

function card(it){
  var p = join(cur, it.name);
  var c = document.createElement('div'); c.className = 'card';
  c.onclick = function(){ openItem(it, p); };
  var t = document.createElement('div'); t.className = 'thumb';
  if (!it.dir && kind(it.name) === 'img'){
    var im = document.createElement('img');
    im.loading = 'lazy';
    im.src = '/api/download?path=' + encodeURIComponent(p);
    im.onerror = function(){ t.textContent = ''; t.appendChild(icon(it)); };
    t.appendChild(im);
  } else t.appendChild(icon(it));
  var f = document.createElement('div'); f.className = 'cf'; f.textContent = it.name;
  c.appendChild(t); c.appendChild(f); c.appendChild(acts(it, p));
  return c;
}

function draw(){
  var box = g('files'); box.textContent = '';
  box.className = view === 'grid' ? 'grid' : '';
  var arr = items.filter(function(it){ return it.name.toLowerCase().indexOf(q) >= 0; });
  g('empty').hidden = arr.length > 0;
  if (view === 'list' && arr.length){
    var h = document.createElement('div'); h.className = 'head';
    h.innerHTML = '<div>Nome</div><div class="c2">Tamanho</div><div class="c3">Modificado</div><div></div>';
    box.appendChild(h);
  }
  arr.forEach(function(it){ box.appendChild(view === 'grid' ? card(it) : row(it)); });
  g('vl').className = view === 'list' ? 'on' : '';
  g('vg').className = view === 'grid' ? 'on' : '';
}
function setView(v){ view = v; try { localStorage.setItem('view', v); } catch (e) {} draw(); }

function post(url, obj){
  var b = new URLSearchParams();
  Object.keys(obj).forEach(function(k){ b.set(k, obj[k]); });
  fetch(url, {method:'POST', body:b}).then(function(){ load(cur); });
}
function mkdir(){
  var name = prompt('Nome da nova pasta:');
  if (name) post('/api/mkdir', {path: cur, name: name});
}
function toggleMenu(e){ e.stopPropagation(); var m = g('menu'); m.hidden = !m.hidden; }
document.addEventListener('click', function(){ g('menu').hidden = true; });

function upload(files){
  files = Array.prototype.slice.call(files);
  if (!files.length) return;
  var dest = cur, i = 0, t = g('toast'), tx = g('ttxt'), bar = g('tbar');
  t.hidden = false;
  function next(){
    if (i >= files.length){
      tx.textContent = 'Envio concluído'; bar.style.width = '100%';
      setTimeout(function(){ t.hidden = true; }, 1800);
      g('f').value = ''; load(cur);
      return;
    }
    tx.textContent = 'Enviando ' + (i + 1) + ' de ' + files.length + ': ' + files[i].name;
    bar.style.width = '0%';
    var fd = new FormData(); fd.append('file', files[i]);
    var x = new XMLHttpRequest();
    x.open('POST', '/api/upload?path=' + encodeURIComponent(dest));
    x.upload.onprogress = function(e){ if (e.lengthComputable) bar.style.width = (e.loaded / e.total * 100) + '%'; };
    x.onload = function(){ i++; next(); };
    x.onerror = function(){ tx.textContent = 'Falha ao enviar ' + files[i].name; i++; setTimeout(next, 1200); };
    x.send(fd);
  }
  next();
}

var dc = 0;
document.addEventListener('dragenter', function(e){ e.preventDefault(); dc++; document.body.classList.add('drag'); });
document.addEventListener('dragleave', function(){ dc--; if (dc <= 0){ dc = 0; document.body.classList.remove('drag'); } });
document.addEventListener('dragover', function(e){ e.preventDefault(); });
document.addEventListener('drop', function(e){
  e.preventDefault(); dc = 0; document.body.classList.remove('drag');
  if (e.dataTransfer.files.length) upload(e.dataTransfer.files);
});

load('');
</script>
</body>
</html>
""".trimIndent()
