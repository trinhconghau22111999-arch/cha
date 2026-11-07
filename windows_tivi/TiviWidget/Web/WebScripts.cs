namespace TiviWidget;

/// <summary>JS YouTube trích NGUYÊN VĂN từ Scripts.kt của bản Android (không sửa nội dung).</summary>
internal static class WebScripts
{
    public const string YtOnlyAd = """
(function(){
 if(window.__wbYtInstalled)return; window.__wbYtInstalled=true;
 if(typeof window.__wbAdSkip==='undefined')window.__wbAdSkip=true;
 var HIDE='.ytp-ad-overlay-container,.ytp-ad-text-overlay,.ytp-ad-image-overlay,.video-ads,#player-ads,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer{display:none!important}';
 var SKIP=['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button','.videoAdUiSkipButton','.ytp-ad-skip-button-container button','.ytp-skip-ad button'];
 var mutedByUs=false,fastByUs=false;
 // Nhớ cờ "đã tắt tiếng do quảng cáo" qua các lần nạp trang (bấm Lùi nạp lại trang): nếu không thì cờ mất, video sau đó bị YouTube giữ im lặng
 try{ mutedByUs=sessionStorage.getItem('__wbMuted')==='1'; }catch(e){}
 function tick(){
  try{
   var on=window.__wbAdSkip!==false;
   var st=document.getElementById('__wb_adcss');
   if(on&&!st&&document.documentElement){st=document.createElement('style');st.id='__wb_adcss';st.textContent=HIDE;document.documentElement.appendChild(st);}
   else if(!on&&st){st.parentNode.removeChild(st);}
   var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
   var p=document.querySelector('.html5-video-player');
   var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));
   if(on&&ad){
    for(var i=0;i<SKIP.length;i++){var b=document.querySelector(SKIP[i]);if(b)b.click();}
    if(v){
     if(!v.muted){v.muted=true;mutedByUs=true;try{sessionStorage.setItem('__wbMuted','1')}catch(e){}}
     if(v.duration&&isFinite(v.duration)){v.currentTime=v.duration;}else{v.playbackRate=16;fastByUs=true;}
    }
   }else if(v){
    if(mutedByUs){
     try{var mp=document.getElementById('movie_player'); if(mp&&mp.unMute) mp.unMute();}catch(e){}
     v.muted=false;mutedByUs=false;try{sessionStorage.removeItem('__wbMuted')}catch(e){}
    }
    if(fastByUs){v.playbackRate=1;fastByUs=false;}
   }
  }catch(e){}
 }
 setInterval(tick,250);
})();
""";

    public const string VoiceHook = """
(function(){
 if(window.__dmVoiceHook)return; window.__dmVoiceHook=true;
 var L=['tìm kiếm bằng giọng nói','tim kiem bang giong noi','search by voice','voice search','search with your voice'];
 function txt(el){return ((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).toLowerCase();}
 function has(t){for(var i=0;i<L.length;i++){if(t.indexOf(L[i])!==-1)return true;}return false;}
 function hook(){
  Array.prototype.forEach.call(document.querySelectorAll('button,[role="button"],a,div[aria-label],span[aria-label]'),function(el){
   if(el.__dmVoice)return;
   if(!has(txt(el)))return;
   el.__dmVoice=true;
   el.addEventListener('click',function(ev){
    if(window.AndroidVoice){ev.preventDefault();ev.stopPropagation();window.AndroidVoice.startVoiceSearch();}
   },true);
  });
 }
 hook(); setInterval(hook,1000);
 try{new MutationObserver(hook).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
""";

    public const string HideOpenApp = """
(function(){
  if (window.__hideOpenApp) return; window.__hideOpenApp = true;
  var st = document.createElement('style');
  st.textContent = 'ytm-open-app-banner-renderer,ytm-mealbar-promo-renderer,ytm-open-app-button-renderer,.open-app-button{display:none!important}';
  (document.head || document.documentElement).appendChild(st);
  var RX = /^(mở ứng dụng|open app|mở app|open in app)$/i;
  var LOGIN = 'https://accounts.google.com/ServiceLogin?service=youtube&hl=vi&continue=' + encodeURIComponent('https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1');
  function relabel(e){
    var w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) { if (/\S/.test(n.nodeValue)) { n.nodeValue = 'Đăng nhập'; } }
    if (e.getAttribute && e.getAttribute('aria-label')) e.setAttribute('aria-label', 'Đăng nhập');
  }
  function sweep(){
    var els = document.querySelectorAll('a,button,ytm-button-renderer,yt-button-shape');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.__oa) continue;
      var t = (e.innerText || e.getAttribute('aria-label') || '').replace(/\s+/g,' ').trim();
      if (t && RX.test(t)) {
        e.__oa = true;
        relabel(e);
        e.addEventListener('click', function(ev){ ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); location.href = LOGIN; }, true);
      }
    }
  }
  var timer = null;
  new MutationObserver(function(){ if (timer) return; timer = setTimeout(function(){ timer = null; sweep(); }, 200); })
    .observe(document.documentElement, {childList:true, subtree:true});
  sweep();
})();
""";

    public const string YtNoMute = """
(function(){
 if(window.__wbNoMute) return; window.__wbNoMute=true;
 function watching(){ var p=location.pathname; return p.indexOf('/watch')===0||p.indexOf('/shorts')===0; }
 function adShowing(){ var p=document.querySelector('.html5-video-player'); return !!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'))); }
 function fix(v){
   try{
     if(!v||!watching()||adShowing()) return;
     var mp=document.getElementById('movie_player');
     if(v.muted||(mp&&mp.isMuted&&mp.isMuted())){ if(mp&&mp.unMute) mp.unMute(); v.muted=false; }
     if(v.volume===0){ if(mp&&mp.setVolume) mp.setVolume(100); v.volume=1; }
   }catch(e){}
 }
 function main(){ return document.querySelector('video.html5-main-video')||document.querySelector('video'); }
 // YouTube hay lưu trạng thái tắt tiếng / âm lượng 0 của lần trước: xóa để video mới không bắt đầu trong im lặng
 try{
   var k='yt-player-volume', raw=localStorage.getItem(k);
   if(raw){ var o=JSON.parse(raw), dd=JSON.parse(o.data); if(dd.muted||!dd.volume){ dd.muted=false; dd.volume=100; o.data=JSON.stringify(dd); localStorage.setItem(k,JSON.stringify(o)); } }
 }catch(e){}
 // Chi kiem tra trong 2 giay dau moi khi mot video bat dau (doi src = video moi; play lai cung video thi bo qua)
 var curSrc=null, timer=null, until=0;
 function stopWin(){ if(timer){ clearInterval(timer); timer=null; } }
 function startWin(v){
   stopWin(); until=Date.now()+2000; fix(v);
   timer=setInterval(function(){ if(Date.now()>=until){ stopWin(); return; } fix(v); },250);
 }
 function onStart(e){
   var v=e.target; if(!v||v.tagName!=='VIDEO') return;
   var src=v.currentSrc||v.src||'';
   if(e.type==='play' && src===curSrc) return;
   curSrc=src; startWin(v);
 }
 ['loadedmetadata','play'].forEach(function(n){ document.addEventListener(n,onStart,true); });
 var first=main(); if(first && !first.paused){ onStart({type:'play',target:first}); }
})();
""";

    public const string YtNav = """
(function(){
 if(window.__wbNavInit) return; window.__wbNavInit=true;
 var ITEM='ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-rich-item-renderer,ytm-media-item,ytm-compact-autoplay-renderer,ytd-compact-video-renderer,ytd-video-renderer,ytd-rich-item-renderer,ytm-playlist-panel-video-renderer,ytd-playlist-panel-video-renderer,ytm-rich-grid-media,ytd-grid-video-renderer,yt-lockup-view-model,ytd-compact-playlist-renderer,ytd-playlist-renderer';
 var cur=null;
 // Viền chọn vẽ quanh TÊN video (không phải khung ảnh): tìm thẻ tiêu đề trong ô video, không có thì dùng cả ô
 function tgt(el){
   var t=null;
   try{ t=el.querySelector('a#video-title,#video-title,h3 a,h3,a.yt-lockup-metadata-view-model-wiz__title,.yt-lockup-metadata-view-model-wiz__title,.media-item-headline,.compact-media-item-headline'); }catch(e){}
   return t||el;
 }
 function paint(el){
   var t=tgt(el); el.__wbT=t;
   t.style.outline='4px solid #00e676'; t.style.outlineOffset='3px'; t.style.borderRadius='6px';
 }
 function unpaint(el){
   var t=el.__wbT||el;
   t.style.outline=''; t.style.outlineOffset=''; t.style.borderRadius='';
   if(t!==el){ el.style.outline=''; el.style.outlineOffset=''; }
   el.__wbT=null;
 }
 function vid(a){try{var u=new URL(a.href,location.href);return u.searchParams.get('v')||u.pathname}catch(e){return a.href}}
 function items(){
   var out=[],seen={};
   var as=document.querySelectorAll('a[href*="/watch"],a[href*="/shorts/"]');
   for(var i=0;i<as.length;i++){
     var a=as[i];
     var r=a.getBoundingClientRect(); if(r.width===0||r.height===0) continue;
     var k=vid(a); if(seen[k]) continue; seen[k]=1;
     out.push(a.closest(ITEM)||a);
   }
   return out;
 }
 var sEl=null, onSearch=false;
 function unmarkSearch(){ if(sEl){ sEl.style.outline=''; sEl.style.outlineOffset=''; } sEl=null; onSearch=false; }
 function typing(){
   var a=document.activeElement;
   return !!(a && (a.tagName==='INPUT' || a.tagName==='TEXTAREA' || a.isContentEditable));
 }
 function visible(el){ var r=el.getBoundingClientRect(); return r.width>0 && r.height>0; }
 // Nut / o tim kiem tren dau trang (ban di dong + ban may tinh); bo qua nut tim bang giong noi
 function searchBtn(){
   var sel='ytm-searchbox button,ytm-searchbox input,ytm-search-icon-button button,button#search-icon-legacy,ytd-searchbox input,ytd-searchbox button,input[name=search_query],button,a,[role=button]';
   var els=document.querySelectorAll(sel), fallback=null;
   for(var i=0;i<els.length;i++){
     var el=els[i]; if(!visible(el)) continue;
     var lab=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')+' '+(el.getAttribute('placeholder')||'')).toLowerCase();
     if(/giong noi|giọng nói|voice|speak/.test(lab)) continue;
     var isBox=el.matches('ytm-searchbox button,ytm-searchbox input,ytm-search-icon-button button,button#search-icon-legacy,ytd-searchbox input,ytd-searchbox button,input[name=search_query]');
     var byLab=/tìm kiếm|tim kiem|search/.test(lab);
     var inHead=!!el.closest('ytm-mobile-topbar-renderer,.mobile-topbar-header,header,ytd-masthead,#masthead-container');
     var r=el.getBoundingClientRect();
     if(isBox || (byLab && (inHead || r.top<120))) return el;
   }
   return fallback;
 }
 function markSearch(el){
   cancelPend();
   if(cur){ unpaint(cur); cur=null; }
   unmarkSearch();
   sEl=el; onSearch=true;
   el.style.outline='4px solid #00e676'; el.style.outlineOffset='-2px';
   window.scrollTo({left:0,top:0,behavior:'smooth'});
 }
 function focusSearchInput(){
   var i=document.querySelector('input[name=search_query],input[type=search],input.searchbox-input,input#search');
   if(i && visible(i)){ i.focus(); try{i.select();}catch(e){} unmarkSearch(); return true; }
   return false;
 }
 function firstItem(l){
   for(var i=0;i<l.length;i++){ var t=l[i].getBoundingClientRect(); if(t.bottom>0 && t.top<innerHeight) return l[i]; }
   return l[0];
 }
 var anchor=null, followT=0, followLast=0;
 function setOutline(el){
   if(cur && cur!==el){ unpaint(cur); }
   cur=el; paint(el);
 }
 // Luot 2 ngon: o chon dung yen tren man hinh, video nao truot vao cho do thi o chon chuyen sang video do
 function followNow(){
   followLast=Date.now();
   if(onSearch || typing() || !cur || !cur.isConnected) return;
   if(!anchor){
     var c=cur.getBoundingClientRect();
     anchor={x:Math.max(0,Math.min(innerWidth-1,c.left+c.width/2)), y:Math.max(0,Math.min(innerHeight-1,c.top+c.height/2))};
   }
   var list=items(), best=null, bestD=Infinity;
   for(var i=0;i<list.length;i++){
     var r=list[i].getBoundingClientRect();
     if(r.bottom<=0||r.top>=innerHeight) continue;
     var inside=anchor.x>=r.left&&anchor.x<=r.right&&anchor.y>=r.top&&anchor.y<=r.bottom;
     var dx=r.left+r.width/2-anchor.x, dy=r.top+r.height/2-anchor.y;
     var dd=inside?-1:(dx*dx+dy*dy);
     if(dd<bestD){ bestD=dd; best=list[i]; }
   }
   if(best && best!==cur) setOutline(best);
 }
 window.__wbFollow=function(){
   if(!cur) return;
   var now=Date.now();
   if(now-followLast>=80){ if(followT){clearTimeout(followT);followT=0;} followNow(); }
   else if(!followT){ followT=setTimeout(function(){ followT=0; followNow(); }, 80-(now-followLast)); }
 };
 // Het video o duoi (danh sach de xuat chua tai them): cuon xuong roi CHO video moi hien ra va chon ngay,
 // khong bat nguoi dung bam lai (truoc day phai doi mot luc o chon moi hien).
 var pendT=0;
 function cancelPend(){ if(pendT){ clearInterval(pendT); pendT=0; } }
 function seek(find){
   cancelPend();
   var tries=0;
   pendT=setInterval(function(){
     tries++;
     var el=null;
     try{ el=find(); }catch(e){}
     if(el){ cancelPend(); mark(el); }
     else if(tries>=30){ cancelPend(); }
   },100);
 }
 function mark(el){
   cancelPend();
   anchor=null;
   unmarkSearch();
   if(cur && cur!==el){ unpaint(cur); }
   cur=el;
   if(el){
     paint(el);
     el.scrollIntoView({block:'center',inline:'nearest',behavior:'smooth'});
     if(window.scrollX) window.scrollTo({left:0,top:window.scrollY,behavior:'auto'});
   }
 }
 window.__wbNav=function(d){
   if(typing()) return false;          // dang go chu: ◀▶ dich con tro chu nhu binh thuong
   if(onSearch) return true;
   var list=items(); if(!list.length) return false;
   if(cur && !cur.isConnected) cur=null;
   var idx=cur?list.indexOf(cur):-1;
   if(idx<0){
     idx=0;
     for(var i=0;i<list.length;i++){var t=list[i].getBoundingClientRect(); if(t.bottom>0 && t.top<innerHeight){idx=i;break;}}
     mark(list[idx]); return true;
   }
   var n=idx+d;
   if(n<0){ return true; }
   if(n>=list.length){
     var last=list[list.length-1];
     window.scrollBy(0, innerHeight*0.6);
     seek(function(){ var l=items(); var i=l.indexOf(last); return (i>=0 && i+1<l.length)?l[i+1]:null; });
     return true;
   }
   mark(list[n]); return true;
 };
 // LEN / XUONG: sang video o hang tren / hang duoi (tim theo vi tri tren man hinh, uu tien video thang cot).
 // Khong co video o hang do thi cuon trang len / xuong (▲ o hang tren cung KHONG con nhay len o tim kiem).
 // Tim video o hang tren (d<0) / hang duoi (d>0) so voi el, theo vi tri tren man hinh. Tra ve chi so hoac -1.
 function pickVert(list, el, d){
   var c=el.getBoundingClientRect(), cx=c.left+c.width/2, cy=c.top+c.height/2;
   var minDy=Math.max(4, c.height*0.5), best=-1, bestS=Infinity;
   for(var i=0;i<list.length;i++){
     if(list[i]===el) continue;
     var r=list[i].getBoundingClientRect();
     var x=r.left+r.width/2, y=r.top+r.height/2;
     var dy=(y-cy)*d;
     if(dy<minDy) continue;
     var sc=dy+Math.abs(x-cx)*2;
     if(sc<bestS){ bestS=sc; best=i; }
   }
   return best;
 }
 window.__wbVert=function(d){
   if(typing()){                        // dang go chu: ▼ thoat o go, chon video; ▲ khong lam gi
     if(d>0){ document.activeElement.blur(); var l0=items(); if(l0.length) mark(firstItem(l0)); }
     return true;
   }
   var list=items();
   if(onSearch){                        // dang o nut tim kiem: ▼ ve hang video dau
     if(d>0){ unmarkSearch(); if(list.length) mark(firstItem(list)); }
     return true;
   }
   if(!list.length) return false;
   if(cur && !cur.isConnected) cur=null;
   var idx=cur?list.indexOf(cur):-1;
   if(idx<0){ return window.__wbNav(0); }
   var best=pickVert(list, cur, d);
   if(best<0){
     window.scrollBy(0, d*innerHeight*0.6);
     if(d>0){
       var base=cur;
       seek(function(){
         if(!base.isConnected) return null;
         var l=items(), b=pickVert(l, base, 1);
         return b>=0 ? l[b] : null;
       });
     }
     return true;
   }
   mark(list[best]); return true;
 };
 // Vao trang ket qua tim kiem (go xong / tim bang giong noi): o chon nam san o video dau tien
 var lastRes='';
 setInterval(function(){
   try{
     if(location.pathname.indexOf('/results')===0 && location.href!==lastRes){
       var l=items(); if(!l.length) return;
       lastRes=location.href;
       var a=document.activeElement; if(a && a.tagName==='INPUT') a.blur();
       mark(firstItem(l));
     }
   }catch(e){}
 },400);
 window.__wbOk=function(){
   if(typing()) return false;
   if(onSearch && sEl){                 // OK tren nut tim kiem: mo trang tim kiem va dat con tro chu vao o nhap
     var t=sEl;
     if(t.tagName==='INPUT'){ t.focus(); try{t.select();}catch(e){} unmarkSearch(); return true; }
     t.click();
     setTimeout(focusSearchInput,350); setTimeout(focusSearchInput,900); setTimeout(focusSearchInput,1600);
     return true;
   }
   if(!cur || !cur.isConnected) return false;
   var a=cur.tagName==='A'?cur:cur.querySelector('a[href*="/watch"],a[href*="/shorts/"]');
   if(!a) return false;
   a.click(); return true;
 };
})();
""";

    public const string HideShorts = """
(function(){
 if(window.__wbNoShorts) return; window.__wbNoShorts=true;
 var SHELF='ytm-reel-shelf-renderer,ytd-reel-shelf-renderer,ytm-reel-item-renderer,ytd-reel-item-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,ytm-pivot-bar-item-renderer[aria-label*="Shorts"]';
 var CARD='ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-rich-item-renderer,ytm-media-item,ytm-rich-grid-media,ytd-rich-item-renderer,ytd-video-renderer,ytd-compact-video-renderer,ytd-grid-video-renderer,ytd-rich-grid-media';
 var st=document.createElement('style'); st.id='__wbNoShortsCss';
 st.textContent=SHELF+'{display:none!important}';
 (document.head||document.documentElement).appendChild(st);
 function hide(el){ if(el && el.style) el.style.setProperty('display','none','important'); }
 function sweep(){
   if(location.pathname.indexOf('/shorts')===0) return;
   try{
     var sh=document.querySelectorAll(SHELF);
     for(var i=0;i<sh.length;i++) hide(sh[i]);
     var as=document.querySelectorAll('a[href*="/shorts/"]');
     for(var j=0;j<as.length;j++){ hide(as[j].closest(CARD+','+SHELF) || as[j]); }
   }catch(e){}
 }
 var t=null;
 function later(){ if(t) return; t=setTimeout(function(){ t=null; sweep(); },300); }
 sweep();
 try{ new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true}); }catch(e){}
 })();
""";

    /// <summary>Phóng video YouTube bằng khung widget (không dùng fullscreen thật vì WebView2 trong widget không hỗ trợ).
    /// window.__wbFill(true/false/undefined=đảo). Nút fullscreen của YouTube cũng chạy qua đây.</summary>
    public const string YtFill = """
(function(){
  if (window.__wbFillInit) return;
  window.__wbFillInit = true;
  var CLS = 'wb-fill';
  function addStyle(){
    if (document.getElementById('wb-fill-style')) return;
    var st = document.createElement('style');
    st.id = 'wb-fill-style';
    st.textContent = 'html.wb-fill,html.wb-fill body{overflow:hidden!important}' +
      'html.wb-fill #movie_player,html.wb-fill .html5-video-player{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;margin:0!important;transform:none!important}' +
      'html.wb-fill .html5-video-container,html.wb-fill video{width:100%!important;height:100%!important;left:0!important;top:0!important;object-fit:contain!important}' +
      'html.wb-fill ytd-masthead,html.wb-fill #masthead-container,html.wb-fill ytm-mobile-topbar-renderer,html.wb-fill .mobile-topbar-header{display:none!important}' +
      // Ẩn danh sách đề xuất / bình luận / thông tin bên dưới, lớp ánh sáng nền (cinematics) và lớp phủ cuối video, để không còn gì đè lên video
      'html.wb-fill #secondary,html.wb-fill #secondary-inner,html.wb-fill #related,html.wb-fill #below,html.wb-fill #comments,html.wb-fill #chat,html.wb-fill #panels,html.wb-fill #playlist,' +
      'html.wb-fill ytd-watch-next-secondary-results-renderer,html.wb-fill ytd-guide-renderer,html.wb-fill ytd-mini-guide-renderer,html.wb-fill tp-yt-app-drawer,html.wb-fill ytd-miniplayer,' +
      'html.wb-fill #cinematics,html.wb-fill #cinematics-container,html.wb-fill .ytp-ce-element,html.wb-fill .ytp-endscreen-content,html.wb-fill .ytp-suggested-action,' +
      'html.wb-fill tp-yt-iron-overlay-backdrop,html.wb-fill ytm-related-chip-cloud-renderer,html.wb-fill .related-items-container{display:none!important}' +
      // Các thẻ cha của video có transform/contain/filter làm khung fixed + z-index bị nhốt bên trong: bỏ đi để video phủ toàn khung
      'html.wb-fill ytd-app,html.wb-fill #content,html.wb-fill ytd-page-manager,html.wb-fill ytd-watch-flexy,html.wb-fill #columns,html.wb-fill #primary,html.wb-fill #primary-inner,' +
      'html.wb-fill #player,html.wb-fill #player-container-outer,html.wb-fill #player-container-inner,html.wb-fill #player-container,html.wb-fill #full-bleed-container,html.wb-fill #player-theater-container,html.wb-fill ytd-player,html.wb-fill .player-container{transform:none!important;contain:none!important;filter:none!important;perspective:none!important;will-change:auto!important;overflow:visible!important;content-visibility:visible!important;opacity:1!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  function active(){ return document.documentElement.classList.contains(CLS); }
  function poke(){ try { window.dispatchEvent(new Event('resize')); } catch(e){} }
  var fsEl = null, lastToggle = 0, enteredAt = 0;
  function playerEl(){ return document.querySelector('#movie_player,.html5-video-player'); }
  // Giả trạng thái fullscreen để YouTube không thấy lệch trạng thái rồi tự gọi exitFullscreen (làm video mở lên rồi tắt ngay)
  function fakeEvents(){
    try { document.dispatchEvent(new Event('fullscreenchange')); } catch(e){}
    try { document.dispatchEvent(new Event('webkitfullscreenchange')); } catch(e){}
  }
  ['fullscreenElement','webkitFullscreenElement','webkitCurrentFullScreenElement'].forEach(function(k){
    try { Object.defineProperty(document, k, {get:function(){return fsEl;}, configurable:true}); } catch(e){}
  });
  try { Object.defineProperty(document, 'webkitIsFullScreen', {get:function(){return !!fsEl;}, configurable:true}); } catch(e){}
  window.__wbFill = function(on){
    var now = Date.now();
    if (on === undefined) {
      if (now - lastToggle < 600) return active();   // bỏ lần bấm dội 2 lần
      on = !active();
    }
    if (!!on === active()) return active();
    if (on && !document.querySelector('video')) return false;
    lastToggle = now;
    addStyle();
    document.documentElement.classList.toggle(CLS, !!on);
    if (on) enteredAt = now;
    fsEl = on ? playerEl() : null;
    fakeEvents();
    poke(); setTimeout(poke, 200); setTimeout(poke, 800);
    return !!on;
  };
  // Nút phóng to của chính YouTube: báo là hỗ trợ, và chạy chế độ phóng theo khung widget
  try { Object.defineProperty(document, 'fullscreenEnabled', {get:function(){return true;}, configurable:true}); } catch(e){}
  try { Object.defineProperty(document, 'webkitFullscreenEnabled', {get:function(){return true;}, configurable:true}); } catch(e){}
  var req = function(){ window.__wbFill(true); return Promise.resolve(); };
  try { Element.prototype.requestFullscreen = req; } catch(e){}
  try { Element.prototype.webkitRequestFullscreen = req; } catch(e){}
  // YouTube có thể tự gọi exitFullscreen ngay sau khi vào: bỏ qua trong 1,5 s đầu
  var ex = function(){ if (Date.now() - enteredAt > 1500) window.__wbFill(false); return Promise.resolve(); };
  try { Document.prototype.exitFullscreen = ex; } catch(e){}
  try { Document.prototype.webkitExitFullscreen = ex; } catch(e){}
  document.addEventListener('keydown', function(e){ if (e.key === 'Escape' && active()) window.__wbFill(false); }, true);
  window.addEventListener('yt-navigate-start', function(){ if (active()) window.__wbFill(false); });
})();
""";

    /// <summary>Trang chủ YouTube chỉ chừa lưới video: ẩn thanh trên cùng (tìm kiếm, đăng nhập), thanh chọn mục và menu bên trái.
    /// Chỉ áp dụng khi đang ở trang chủ (đường dẫn "/"); vào video hay trang khác thì giao diện như cũ.</summary>
    public const string YtHomeClean = """
(function(){
  if (window.__wbHomeInit) return;
  window.__wbHomeInit = true;
  var st = document.createElement('style');
  st.id = 'wb-home-style';
  st.textContent =
    'html.wb-home ytd-masthead,html.wb-home #masthead-container,html.wb-home ytd-mini-guide-renderer,html.wb-home ytd-guide-renderer,html.wb-home tp-yt-app-drawer,' +
    'html.wb-home #chips-wrapper,html.wb-home ytd-feed-filter-chip-bar-renderer,html.wb-home ytd-rich-grid-renderer #header,' +
    'html.wb-home ytm-mobile-topbar-renderer,html.wb-home .mobile-topbar-header,html.wb-home ytm-chip-cloud-renderer,html.wb-home .chip-cloud{display:none!important}' +
    'html.wb-home ytd-app{--ytd-persistent-guide-width:0px!important;--ytd-mini-guide-width:0px!important;--ytd-masthead-height:0px!important;--ytd-toolbar-height:0px!important}' +
    'html.wb-home ytd-page-manager,html.wb-home #page-manager{margin-top:0!important;margin-left:0!important}' +
    'html.wb-home ytd-browse,html.wb-home ytd-two-column-browse-results-renderer{padding-top:0!important}' +
    'html.wb-home ytd-app[mini-guide-visible] ytd-page-manager,html.wb-home ytd-app[guide-persistent-and-visible] ytd-page-manager{margin-left:0!important}';
  (document.head || document.documentElement).appendChild(st);
  function sync(){
    var home = location.pathname === '/' || location.pathname === '';
    document.documentElement.classList.toggle('wb-home', home);
  }
  sync();
  window.addEventListener('yt-navigate-finish', sync);
  window.addEventListener('popstate', sync);
  setInterval(sync, 700);
})();
""";

    /// <summary>Bản có cờ __wbAdSkip như configureWebView() bên Android.</summary>
    public const string YtOnlyAdWithFlag = "window.__wbAdSkip=true;" + YtOnlyAd;

    // Giả object AndroidVoice để VoiceHook dùng được: nút mic YouTube gửi "voice" về app
    private const string AndroidVoiceShim = "window.AndroidVoice={startVoiceSearch:function(){try{window.chrome.webview.postMessage('voice');}catch(e){}}};";

    /// <summary>Chạy ngay khi document được tạo (trước script của trang), giống shouldInterceptRequest + DOCUMENT_START_SCRIPT bên Android.</summary>
    public const string YtDocStart = "window.__wbAdSkip=true;" + AndroidVoiceShim +
        "if (/(^|\\.)youtube\\.com$/.test(location.hostname)) {" + YtOnlyAd + HideOpenApp + YtFill + "}";

    /// <summary>Nhập văn bản từ điện thoại (port TEXT_EDIT_FN). Gọi: (fn)(del, "chuỗi JSON").</summary>
    public const string TextEditFn = """
function(del, ins) {
  var el = document.activeElement;
  try {
    while (el && el.tagName === 'IFRAME' && el.contentDocument && el.contentDocument.activeElement) {
      el = el.contentDocument.activeElement;
    }
  } catch (e) {}
  if (!el) return false;
  var doc = el.ownerDocument;
  function stepBack(str, idx, n) {
    while (n > 0 && idx > 0) {
      idx--;
      var c = str.charCodeAt(idx);
      if (c >= 0xDC00 && c <= 0xDFFF && idx > 0) {
        var h = str.charCodeAt(idx - 1);
        if (h >= 0xD800 && h <= 0xDBFF) idx--;
      }
      n--;
    }
    return idx;
  }
  function fire(target) {
    try {
      target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: ins ? 'insertText' : 'deleteContentBackward', data: ins || null }));
    } catch (e) {
      target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    var v = el.value;
    var s = null, e2 = null;
    try { s = el.selectionStart; e2 = el.selectionEnd; } catch (err) {}
    if (s === null || e2 === null) { s = e2 = v.length; }
    var start = (e2 === s) ? stepBack(v, s, del) : s;
    try {
      el.setRangeText(ins, start, e2, 'end');
    } catch (err) {
      var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v.slice(0, start) + ins + v.slice(e2));
    }
    fire(el);
    return true;
  }
  if (el.isContentEditable) {
    for (var k = 0; k < del; k++) doc.execCommand('delete');
    if (ins) doc.execCommand('insertText', false, ins);
    return true;
  }
  return false;
}
""";
}
