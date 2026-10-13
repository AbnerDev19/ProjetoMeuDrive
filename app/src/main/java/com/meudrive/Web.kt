package com.meudrive

val WEB_PAGE = """
<!doctype html>
<html lang="pt-br">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Meu Drive</title>
<style>
*{box-sizing:border-box}
body{font-family:system-ui,sans-serif;margin:0;background:#f6f7f9;color:#1f2430}
header{background:#fff;border-bottom:1px solid #e3e6ec;padding:14px 20px;font-size:18px}
.wrap{max-width:860px;margin:0 auto;padding:16px}
.box{max-width:340px;margin:12vh auto;background:#fff;padding:24px;border-radius:12px;border:1px solid #e3e6ec}
input[type=password]{width:100%;padding:10px;margin:12px 0;border:1px solid #ccd;border-radius:8px}
button{padding:9px 14px;border:0;border-radius:8px;background:#2563eb;color:#fff;cursor:pointer;margin:2px}
button.red{background:#dc2626}
button.gray{background:#64748b}
.bar{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-bottom:10px}
#drop{border:2px dashed #b8c0d0;border-radius:10px;padding:14px;text-align:center;color:#64748b;margin-bottom:12px}
ul{list-style:none;padding:0;margin:0;background:#fff;border:1px solid #e3e6ec;border-radius:10px}
li{display:flex;align-items:center;gap:10px;padding:10px 14px;border-bottom:1px solid #eef0f4}
li:last-child{border-bottom:0}
li .n{flex:1;word-break:break-all;cursor:pointer}
li .s{color:#64748b;font-size:13px;white-space:nowrap}
#crumbs a{color:#2563eb;cursor:pointer}
progress{width:100%}
</style>
</head>
<body>
<div id="login" class="box" hidden>
  <h2 style="margin-top:0">Meu Drive</h2>
  <input id="pw" type="password" placeholder="Senha" onkeydown="if(event.key==='Enter')login()">
  <button onclick="login()">Entrar</button>
  <p id="err" style="color:#dc2626"></p>
</div>

<div id="app" hidden>
  <header><b>Meu Drive</b></header>
  <div class="wrap">
    <div id="crumbs" style="margin-bottom:10px"></div>
    <div class="bar">
      <input type="file" id="f" multiple>
      <button onclick="upload()">Enviar</button>
      <button class="gray" onclick="mkdir()">Nova pasta</button>
    </div>
    <progress id="prog" value="0" max="100" hidden></progress>
    <div id="drop">Arraste arquivos aqui para enviar</div>
    <ul id="list"></ul>
  </div>
</div>

<script>
var cur = '';
function show(ok){ document.getElementById('login').hidden = ok; document.getElementById('app').hidden = !ok; }
function sz(n){
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n/1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n/1048576).toFixed(1) + ' MB';
  return (n/1073741824).toFixed(2) + ' GB';
}
function join(a, b){ return a ? a + '/' + b : b; }

function login(){
  var b = new URLSearchParams();
  b.set('password', document.getElementById('pw').value);
  fetch('/api/login', {method:'POST', body:b}).then(function(r){
    if (r.ok){ show(true); load(''); }
    else document.getElementById('err').textContent = 'Senha incorreta';
  });
}

function load(p){
  cur = p;
  fetch('/api/list?path=' + encodeURIComponent(p)).then(function(r){
    if (r.status === 401){ show(false); return null; }
    return r.json();
  }).then(function(d){ if (d){ show(true); render(d); } });
}

function render(d){
  var c = document.getElementById('crumbs');
  c.textContent = '';
  var home = document.createElement('a');
  home.textContent = 'Inicio';
  home.onclick = function(){ load(''); };
  c.appendChild(home);
  var acc = '';
  d.path.split('/').filter(Boolean).forEach(function(seg){
    acc = join(acc, seg);
    var target = acc;
    c.appendChild(document.createTextNode(' / '));
    var a = document.createElement('a');
    a.textContent = seg;
    a.onclick = function(){ load(target); };
    c.appendChild(a);
  });

  var ul = document.getElementById('list');
  ul.textContent = '';
  if (d.items.length === 0){
    var e = document.createElement('li');
    e.textContent = 'Pasta vazia';
    ul.appendChild(e);
  }
  d.items.forEach(function(it){
    var li = document.createElement('li');
    var n = document.createElement('span');
    n.className = 'n';
    n.textContent = (it.dir ? '[pasta] ' : '') + it.name;
    var path = join(d.path, it.name);
    n.onclick = function(){
      if (it.dir) load(path);
      else location.href = '/api/download?path=' + encodeURIComponent(path);
    };
    var s = document.createElement('span');
    s.className = 's';
    s.textContent = it.dir ? '' : sz(it.size);
    var del = document.createElement('button');
    del.className = 'red';
    del.textContent = 'Apagar';
    del.onclick = function(){
      if (confirm('Apagar ' + it.name + '?')) post('/api/delete', {path: path});
    };
    li.appendChild(n); li.appendChild(s); li.appendChild(del);
    ul.appendChild(li);
  });
}

function post(url, obj){
  var b = new URLSearchParams();
  Object.keys(obj).forEach(function(k){ b.set(k, obj[k]); });
  fetch(url, {method:'POST', body:b}).then(function(){ load(cur); });
}

function mkdir(){
  var name = prompt('Nome da nova pasta:');
  if (name) post('/api/mkdir', {path: cur, name: name});
}

function upload(files){
  files = files || document.getElementById('f').files;
  var prog = document.getElementById('prog');
  var i = 0;
  function next(){
    if (i >= files.length){
      prog.hidden = true;
      document.getElementById('f').value = '';
      load(cur);
      return;
    }
    var fd = new FormData();
    fd.append('file', files[i]);
    var x = new XMLHttpRequest();
    x.open('POST', '/api/upload?path=' + encodeURIComponent(cur));
    x.upload.onprogress = function(e){
      if (e.lengthComputable){ prog.hidden = false; prog.value = e.loaded / e.total * 100; }
    };
    x.onload = function(){ i++; next(); };
    x.onerror = function(){ alert('Falha ao enviar ' + files[i].name); i++; next(); };
    x.send(fd);
  }
  next();
}

var drop = document.getElementById('drop');
document.body.ondragover = function(e){ e.preventDefault(); };
document.body.ondrop = function(e){
  e.preventDefault();
  if (e.dataTransfer.files.length) upload(e.dataTransfer.files);
};

fetch('/api/list?path=').then(function(r){
  if (r.ok){ show(true); r.json().then(render); } else show(false);
});
</script>
</body>
</html>
""".trimIndent()
