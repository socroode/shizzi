package dev.shizzi.conso

internal object LocalMediaWebSupport {
    fun shouldKeepNetworkAvailable(hasWifiTransport: Boolean): Boolean =
        hasWifiTransport

    val fullscreenScript: String = """
        (function(){
          if(window.__shizziFullscreenInstalled)return;
          window.__shizziFullscreenInstalled=true;

          function enterFullscreen(video){
            try{
              if(video.requestFullscreen){
                var result=video.requestFullscreen();
                if(result&&result.catch)result.catch(function(){});
                return;
              }
              if(video.webkitRequestFullscreen){
                video.webkitRequestFullscreen();
                return;
              }
              if(video.webkitEnterFullscreen){
                video.webkitEnterFullscreen();
              }
            }catch(_){}
          }

          function install(){
            var videos=document.querySelectorAll('video');
            videos.forEach(function(video){
              if(video.dataset.shizziFullscreenReady==='1')return;
              video.dataset.shizziFullscreenReady='1';

              var parent=video.parentElement||document.body;
              var button=document.createElement('button');
              button.type='button';
              button.textContent='⛶ Plein écran';
              button.className='shizzi-fullscreen-button';
              button.setAttribute('aria-label','Plein écran');
              button.style.cssText='display:block;width:100%;margin:10px 0 0;padding:13px 16px;border:0;border-radius:14px;background:#2563eb;color:#fff;font:700 16px system-ui,sans-serif;';
              button.addEventListener('click',function(){enterFullscreen(video)});
              parent.appendChild(button);
            });
          }

          install();
          new MutationObserver(install).observe(document.documentElement,{childList:true,subtree:true});
        })();
    """.trimIndent()
}
