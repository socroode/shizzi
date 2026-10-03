package dev.shizzi

internal object MessagingWebUi {
    fun page(): String = """
        <!doctype html>
        <html lang="fr">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
          <title>Messagerie · Shizzi+</title>
          <style>
            :root{color-scheme:dark;font-family:Inter,system-ui,-apple-system,sans-serif;background:#07111f;color:#f8fafc}
            *{box-sizing:border-box}body{margin:0;background:#07111f}
            button,input{font:inherit}.app{height:100vh;display:grid;grid-template-columns:340px 1fr}
            aside{border-right:1px solid #ffffff16;background:#0b1728;display:flex;flex-direction:column;min-height:0}
            .brand{padding:20px;border-bottom:1px solid #ffffff12}.brand h1{margin:0;font-size:24px}.muted{color:#94a3b8}
            .toolbar{padding:12px;display:grid;grid-template-columns:1fr auto;gap:8px}
            .toolbar input,.composer input,.group input{border:1px solid #334155;background:#111d30;color:#fff;border-radius:14px;padding:12px}
            .toolbar button,.composer button,.group button{border:0;border-radius:14px;padding:12px 14px;background:#22d3ee;color:#06202a;font-weight:800}
            .list{overflow:auto;padding:6px 10px 16px}.row{width:100%;text-align:left;border:0;background:transparent;color:#fff;border-radius:14px;padding:12px;display:grid;grid-template-columns:1fr auto;gap:4px}
            .row:hover,.row.active{background:#ffffff0c}.name{font-weight:800}.preview{grid-column:1/2;color:#94a3b8;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .badge{align-self:center;background:#38bdf8;color:#061827;border-radius:999px;padding:3px 8px;font-size:12px;font-weight:900}.online{color:#34d399}.offline{color:#64748b}
            main{display:flex;flex-direction:column;min-width:0;background:linear-gradient(160deg,#081522,#101827)}
            .head{padding:13px 16px;border-bottom:1px solid #ffffff12;display:flex;align-items:center;gap:12px;min-height:70px}.head-info{min-width:0;flex:1}.head strong{font-size:19px}
            .back{display:none;border:0;background:transparent;color:#7dd3fc;font-size:24px}.call-actions{display:flex;gap:8px}.call-actions[hidden]{display:none}
            .call-button{border:1px solid #ffffff20;background:#12233a;color:#e2f6ff;border-radius:999px;width:44px;height:44px;font-size:20px}
            .messages{flex:1;overflow:auto;padding:18px;display:flex;flex-direction:column;gap:10px}
            .empty{margin:auto;color:#94a3b8;text-align:center;max-width:360px}.bubble{max-width:min(78%,620px);padding:11px 14px;border-radius:17px;background:#17243a;align-self:flex-start}
            .bubble.mine{background:#164e63;align-self:flex-end}.meta{font-size:11px;color:#9fb1c5;margin-bottom:4px}.body{white-space:pre-wrap;overflow-wrap:anywhere}
            .composer{display:grid;grid-template-columns:1fr auto;gap:10px;padding:14px;border-top:1px solid #ffffff12}.composer[hidden]{display:none}
            dialog{border:1px solid #ffffff20;border-radius:22px;background:#0f172a;color:#fff;width:min(92vw,520px);padding:20px}.group{display:grid;gap:12px}
            .members{max-height:240px;overflow:auto;display:grid;gap:8px}.member{display:flex;gap:10px;align-items:center}.member input{width:18px;height:18px}

            .call-overlay{position:fixed;inset:0;z-index:9999;background:#020817f2;display:grid;place-items:center;padding:12px}
            .call-overlay[hidden]{display:none}.call-card{width:min(100%,760px);height:min(94vh,900px);border:1px solid #ffffff18;border-radius:28px;background:#0a1424;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 30px 80px #0009}
            .call-stage{position:relative;flex:1;min-height:280px;background:#020617;display:grid;place-items:center;overflow:hidden}
            .call-stage video{width:100%;height:100%;object-fit:cover;background:#020617}.call-stage.audio-only #remoteVideo,.call-stage.audio-only #localVideo{display:none}
            .audio-avatar{display:none;width:120px;height:120px;border-radius:999px;background:#164e63;place-items:center;font-size:48px;font-weight:900;box-shadow:0 0 0 14px #164e6338}
            .call-stage.audio-only .audio-avatar{display:grid}.local-video{position:absolute;right:14px;bottom:14px;width:min(30%,190px)!important;height:min(27%,240px)!important;border-radius:18px;border:2px solid #ffffff55;object-fit:cover;transform:scaleX(-1);background:#111827}
            .call-stage.audio-only .local-video{display:none!important}.call-info{padding:16px 18px 8px;text-align:center}.call-peer{font-size:22px;font-weight:900}.call-status{color:#94a3b8;margin-top:4px;min-height:22px}
            .call-controls{display:flex;flex-wrap:wrap;justify-content:center;gap:10px;padding:12px 14px 22px}.call-controls[hidden]{display:none}
            .round-control{border:0;border-radius:999px;min-width:58px;height:58px;padding:0 17px;background:#1e293b;color:#fff;font-weight:800}.round-control.active{background:#475569}.round-control.accept{background:#22c55e;color:#052e16}.round-control.danger{background:#ef4444;color:#fff}
            .call-note{font-size:12px;color:#64748b;text-align:center;padding:0 18px 16px}

            @media(max-width:760px){
              .app{grid-template-columns:1fr}aside{display:flex}.app.chat-open aside{display:none}.app:not(.chat-open) main{display:none}.back{display:block}
              .call-card{height:100%;border-radius:0}.call-overlay{padding:0}.call-stage{min-height:0}.round-control{min-width:54px;height:54px;padding:0 14px}
            }
          </style>
        </head>
        <body>
        <div id="app" class="app">
          <aside>
            <div class="brand"><h1>Messagerie Shizzi</h1><div id="self" class="muted">Connexion…</div></div>
            <div class="toolbar"><input id="search" placeholder="Rechercher"><button id="newGroup">+ Groupe</button></div>
            <div id="list" class="list"></div>
          </aside>
          <main>
            <div class="head">
              <button id="back" class="back">‹</button>
              <div class="head-info"><strong id="title">Messagerie locale</strong><div id="presence" class="muted">Choisis une conversation</div></div>
              <div id="callActions" class="call-actions" hidden>
                <button id="audioCall" class="call-button" type="button" title="Appel vocal">☎</button>
                <button id="videoCall" class="call-button" type="button" title="Appel vidéo">▣</button>
              </div>
            </div>
            <div id="messages" class="messages"><div class="empty">Messages privés et groupes restent sur le réseau local Shizzi et n'utilisent pas le quota Internet.</div></div>
            <form id="composer" class="composer" hidden><input id="text" maxlength="2000" autocomplete="off" placeholder="Écrire un message…"><button>Envoyer</button></form>
          </main>
        </div>

        <dialog id="groupDialog"><form id="groupForm" class="group" method="dialog">
          <h2>Nouveau groupe</h2><input id="groupName" maxlength="60" placeholder="Nom du groupe" required>
          <div id="groupMembers" class="members"></div>
          <div style="display:flex;gap:8px;justify-content:flex-end"><button type="button" id="cancelGroup">Annuler</button><button type="submit">Créer</button></div>
        </form></dialog>

        <div id="callOverlay" class="call-overlay" hidden>
          <div class="call-card">
            <div id="callStage" class="call-stage audio-only">
              <video id="remoteVideo" autoplay playsinline></video>
              <video id="localVideo" class="local-video" autoplay muted playsinline></video>
              <audio id="remoteAudio" autoplay></audio>
              <div id="audioAvatar" class="audio-avatar">☎</div>
            </div>
            <div class="call-info">
              <div id="callPeer" class="call-peer">Appel Shizzi</div>
              <div id="callStatus" class="call-status">Préparation…</div>
            </div>
            <div id="incomingControls" class="call-controls" hidden>
              <button id="acceptCall" class="round-control accept" type="button">Décrocher</button>
              <button id="rejectCall" class="round-control danger" type="button">Refuser</button>
            </div>
            <div id="activeControls" class="call-controls" hidden>
              <button id="muteCall" class="round-control" type="button">Micro</button>
              <button id="cameraCall" class="round-control" type="button">Caméra</button>
              <button id="flipCamera" class="round-control" type="button">Retourner</button>
              <button id="hangupCall" class="round-control danger" type="button">Raccrocher</button>
            </div>
            <div class="call-note">Audio/vidéo WebRTC local entre les téléphones connectés au Wi‑Fi Shizzi.</div>
          </div>
        </div>

        <script>
        (function(){
          var selected="", state=null;
          var app=document.getElementById("app"), list=document.getElementById("list"), messages=document.getElementById("messages");
          var composer=document.getElementById("composer"), text=document.getElementById("text"), search=document.getElementById("search");
          var groupDialog=document.getElementById("groupDialog"), groupMembers=document.getElementById("groupMembers");
          var callActions=document.getElementById("callActions"), callOverlay=document.getElementById("callOverlay"), callStage=document.getElementById("callStage");
          var remoteVideo=document.getElementById("remoteVideo"), localVideo=document.getElementById("localVideo"), remoteAudio=document.getElementById("remoteAudio");
          var incomingControls=document.getElementById("incomingControls"), activeControls=document.getElementById("activeControls");
          var callPeer=document.getElementById("callPeer"), callStatus=document.getElementById("callStatus");
          var currentCall=null, incomingCall=null, pc=null, localStream=null, callCursor=0;
          var localCandidateQueue=[], remoteCandidateQueues={}, muted=false, cameraEnabled=true, facingMode="user", callPollBusy=false;

          function time(ms){if(!ms)return"";var d=new Date(ms);return d.toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"})}
          function userByNumber(n){return state&&state.users ? state.users.find(function(u){return u.number===n}) : null}
          function currentConversation(){return state&&state.conversations ? state.conversations.find(function(c){return c.id===selected}) : null}
          function directPeer(c){
            if(!c||c.type!=="direct"||!state)return null;
            var number=c.members.find(function(n){return n!==state.self.number});
            var user=userByNumber(number);
            return {number:number,name:user&&user.name?user.name:number,online:Boolean(user&&user.online)};
          }

          function nativeBridgeAvailable(){
            return Boolean(window.ShizziNativeBridge&&typeof window.ShizziNativeBridge.request==="function");
          }
          function startIncomingRingtone(){
            if(window.ShizziNativeBridge&&typeof window.ShizziNativeBridge.startIncomingRingtone==="function"){
              try{window.ShizziNativeBridge.startIncomingRingtone()}catch(_){}
            }
          }

          function startOutgoingRingback(){
            if(window.ShizziNativeBridge&&typeof window.ShizziNativeBridge.startOutgoingRingback==="function"){
              try{window.ShizziNativeBridge.startOutgoingRingback()}catch(_){}
            }
          }

          function stopCallTone(){
            if(window.ShizziNativeBridge&&typeof window.ShizziNativeBridge.stopCallTone==="function"){
              try{window.ShizziNativeBridge.stopCallTone()}catch(_){}
            }
          }


          async function api(path,method,payload){
            if(nativeBridgeAvailable()){
              try{
                var nativeBody=payload===undefined?"":JSON.stringify(payload);
                var nativeRaw=window.ShizziNativeBridge.request(path,method||"GET",nativeBody);
                return JSON.parse(nativeRaw||'{"ok":false,"message":"Réponse locale vide."}');
              }catch(_){
                return {ok:false,message:"Pont local Shizzi+ indisponible."};
              }
            }
            var options={method:method||"GET",cache:"no-store"};
            if(payload!==undefined){
              options.headers={"Content-Type":"application/json"};
              options.body=JSON.stringify(payload);
            }
            var response=await fetch("/chat/api/"+path,options);
            var data=await response.json().catch(function(){return {ok:false,message:"Réponse invalide."}});
            if(!response.ok&&data.ok!==false)data.ok=false;
            return data;
          }

          function renderList(){
            if(!state)return; var q=search.value.trim().toLowerCase(); list.textContent="";
            state.conversations.filter(function(c){return !q||c.name.toLowerCase().includes(q)}).forEach(function(c){
              var b=document.createElement("button"); b.className="row"+(c.id===selected?" active":""); b.dataset.id=c.id;
              var n=document.createElement("div"); n.className="name"; n.textContent=(c.type==="group"?"👥 ":"")+c.name; b.appendChild(n);
              if(c.unread>0){var badge=document.createElement("span");badge.className="badge";badge.textContent=c.unread;b.appendChild(badge)}
              var p=document.createElement("div");p.className="preview";p.textContent=c.lastMessage||"Aucun message";b.appendChild(p);
              b.onclick=function(){selected=c.id;app.classList.add("chat-open");refresh(true)}; list.appendChild(b);
            });
          }

          function renderMessages(){
            var c=currentConversation(); messages.textContent="";
            if(!c){
              messages.innerHTML='<div class="empty">Choisis une conversation.</div>';
              composer.hidden=true;callActions.hidden=true;return;
            }
            document.getElementById("title").textContent=c.name; composer.hidden=false;
            if(c.type==="direct"){
              var peer=directPeer(c);
              document.getElementById("presence").textContent=peer&&peer.online?"● En ligne":"Hors ligne";
              document.getElementById("presence").className=peer&&peer.online?"online":"offline";
              callActions.hidden=false;
            }else{
              document.getElementById("presence").textContent=c.members.length+" membres";
              document.getElementById("presence").className="muted";
              callActions.hidden=true;
            }
            if(!state.messages.length){messages.innerHTML='<div class="empty">Aucun message. Écris le premier.</div>';return}
            state.messages.forEach(function(m){
              var b=document.createElement("div");b.className="bubble"+(m.sender===state.self.number?" mine":"");
              var meta=document.createElement("div");meta.className="meta";meta.textContent=m.senderName+" · "+time(m.createdAtMillis);
              var body=document.createElement("div");body.className="body";body.textContent=m.text;b.appendChild(meta);b.appendChild(body);messages.appendChild(b);
            });
            messages.scrollTop=messages.scrollHeight;
          }

          function render(){
            if(!state)return;document.getElementById("self").textContent=state.self.name+" · "+state.self.number;
            renderList();renderMessages();
            var total=state.conversations.reduce(function(a,c){return a+Number(c.unread||0)},0);
            document.title=(total>0?"("+total+") ":"")+"Messagerie · Shizzi+";
          }

          async function refresh(markRead){
            try{
              var path="snapshot"+(selected?"?conversation="+encodeURIComponent(selected)+"&markRead="+(markRead?"1":"0"):"");
              var j=await api(path,"GET");if(!j.ok)return;state=j;
              if(selected&&!state.conversations.some(function(c){return c.id===selected}))selected="";
              render();
            }catch(_){}
          }

          composer.onsubmit=async function(e){
            e.preventDefault();var value=text.value.trim();if(!selected||!value)return;text.value="";
            await api("send","POST",{conversationId:selected,text:value});refresh(true);
          };
          search.oninput=renderList;
          document.getElementById("back").onclick=function(){selected="";app.classList.remove("chat-open");refresh(false)};

          document.getElementById("newGroup").onclick=function(){
            if(!state)return;document.getElementById("groupName").value="";groupMembers.textContent="";
            state.users.filter(function(u){return u.number!==state.self.number}).forEach(function(u){
              var label=document.createElement("label");label.className="member";var cb=document.createElement("input");cb.type="checkbox";cb.value=u.number;
              var span=document.createElement("span");span.textContent=u.name+" · "+u.number;label.appendChild(cb);label.appendChild(span);groupMembers.appendChild(label);
            });groupDialog.showModal();
          };
          document.getElementById("cancelGroup").onclick=function(){groupDialog.close()};
          document.getElementById("groupForm").onsubmit=async function(e){
            e.preventDefault();var name=document.getElementById("groupName").value.trim();
            var members=Array.from(groupMembers.querySelectorAll('input:checked')).map(function(x){return x.value});
            var j=await api("group/create","POST",{name:name,members:members});
            if(j.ok){selected=j.conversationId;groupDialog.close();app.classList.add("chat-open");refresh(true)}
          };

          function mediaConstraints(kind){
            return {
              audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},
              video:kind==="video"?{facingMode:{ideal:facingMode},width:{ideal:1280},height:{ideal:720}}:false
            };
          }

          async function acquireLocal(kind){
            if(!window.isSecureContext){
              throw new Error("Le mode d'appel sécurisé Shizzi+ n'est pas actif.");
            }
            if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia){
              throw new Error("Shizzi+ n'a pas accès au micro ou à la caméra.");
            }
            localStream=await navigator.mediaDevices.getUserMedia(mediaConstraints(kind));
            if(kind==="video"){
              localVideo.srcObject=localStream;
              callStage.classList.remove("audio-only");
            }else{
              localVideo.srcObject=null;
              callStage.classList.add("audio-only");
            }
            muted=false;cameraEnabled=true;updateControlLabels();
          }

          function updateControlLabels(){
            var mute=document.getElementById("muteCall"), camera=document.getElementById("cameraCall");
            mute.textContent=muted?"Réactiver micro":"Micro";
            mute.classList.toggle("active",muted);
            camera.textContent=cameraEnabled?"Caméra":"Réactiver caméra";
            camera.classList.toggle("active",!cameraEnabled);
            var video=currentCall&&currentCall.kind==="video";
            camera.hidden=!video;document.getElementById("flipCamera").hidden=!video;
          }

          function showCall(kind,peerName,incoming){
            callOverlay.hidden=false;
            callPeer.textContent=peerName||"Appel Shizzi";
            callStage.classList.toggle("audio-only",kind!=="video");
            incomingControls.hidden=!incoming;
            activeControls.hidden=incoming;
            callStatus.textContent=incoming?(kind==="video"?"Appel vidéo entrant":"Appel vocal entrant"):"Connexion…";
            updateControlLabels();
          }

          function makePeerConnection(){
            pc=new RTCPeerConnection({iceServers:[],bundlePolicy:"max-bundle"});
            localCandidateQueue=[];
            if(localStream){
              localStream.getTracks().forEach(function(track){pc.addTrack(track,localStream)});
            }
            pc.onicecandidate=function(event){
              if(!event.candidate)return;
              var payload=event.candidate.toJSON?event.candidate.toJSON():{
                candidate:event.candidate.candidate,
                sdpMid:event.candidate.sdpMid,
                sdpMLineIndex:event.candidate.sdpMLineIndex
              };
              if(currentCall&&currentCall.id){
                sendIce(currentCall.id,payload);
              }else{
                localCandidateQueue.push(payload);
              }
            };
            pc.ontrack=function(event){
              var stream=event.streams&&event.streams[0];
              if(!stream)return;
              if(currentCall&&currentCall.kind==="video"){
                remoteVideo.srcObject=stream;
                remoteAudio.srcObject=null;
              }else{
                remoteAudio.srcObject=stream;
                remoteVideo.srcObject=null;
              }
            };
            pc.onconnectionstatechange=function(){
              if(!pc||!currentCall)return;
              var value=pc.connectionState;
              if(value==="connected"){
                callStatus.textContent="En appel";
              }else if(value==="connecting"||value==="new"){
                callStatus.textContent="Connexion locale…";
              }else if(value==="failed"){
                endCurrentCall(true,"Connexion interrompue.");
              }
            };
          }

          async function sendIce(callId,candidate){
            try{await api("call/ice","POST",{callId:callId,candidate:candidate})}catch(_){}
          }

          async function flushLocalCandidates(){
            if(!currentCall||!currentCall.id)return;
            var pending=localCandidateQueue.splice(0);
            for(var i=0;i<pending.length;i++)await sendIce(currentCall.id,pending[i]);
          }

          function queueRemoteCandidate(callId,candidate){
            if(!remoteCandidateQueues[callId])remoteCandidateQueues[callId]=[];
            remoteCandidateQueues[callId].push(candidate);
          }

          async function flushRemoteCandidates(callId){
            if(!pc||!pc.remoteDescription)return;
            var queue=remoteCandidateQueues[callId]||[];
            delete remoteCandidateQueues[callId];
            for(var i=0;i<queue.length;i++){
              try{await pc.addIceCandidate(queue[i])}catch(_){}
            }
          }

          async function startCall(kind){
            if(currentCall||incomingCall)return;
            var peer=directPeer(currentConversation());
            if(!peer)return;
            currentCall={id:"",kind:kind,peer:peer.number,peerName:peer.name,outgoing:true};
            showCall(kind,peer.name,false);
            callStatus.textContent=kind==="video"?"Préparation de la caméra…":"Préparation du micro…";
            try{
              await acquireLocal(kind);
              makePeerConnection();
              var offer=await pc.createOffer();
              await pc.setLocalDescription(offer);
              var result=await api("call/start","POST",{
                target:peer.number,
                kind:kind,
                offer:{type:pc.localDescription.type,sdp:pc.localDescription.sdp}
              });
              if(!result.ok)throw new Error(result.message||"Appel impossible.");
              currentCall.id=result.callId;
              callStatus.textContent="Appel de "+peer.name+"…";
              startOutgoingRingback();
              await flushLocalCandidates();
            }catch(error){
              var message=error&&error.message?error.message:"Impossible de démarrer l'appel.";
              closeCallUi();
              alert(message);
            }
          }

          function showIncoming(event){
            if(currentCall||incomingCall){
              api("call/reject","POST",{callId:event.callId}).catch(function(){});
              return;
            }
            incomingCall=event;
            showCall(event.kind,event.fromName||event.from,true);
            startIncomingRingtone();
            if(navigator.vibrate)navigator.vibrate([250,150,250,150,450,150,450]);
          }

          async function acceptIncoming(){
            if(!incomingCall)return;
            var event=incomingCall;incomingCall=null;
            currentCall={
              id:event.callId,
              kind:event.kind,
              peer:event.from,
              peerName:event.fromName||event.from,
              outgoing:false
            };
            showCall(currentCall.kind,currentCall.peerName,false);
            callStatus.textContent=currentCall.kind==="video"?"Ouverture de la caméra…":"Ouverture du micro…";
            stopCallTone();
            if(navigator.vibrate)navigator.vibrate(0);
            try{
              await acquireLocal(currentCall.kind);
              makePeerConnection();
              await pc.setRemoteDescription(event.description);
              await flushRemoteCandidates(currentCall.id);
              var answer=await pc.createAnswer();
              await pc.setLocalDescription(answer);
              var result=await api("call/answer","POST",{
                callId:currentCall.id,
                answer:{type:pc.localDescription.type,sdp:pc.localDescription.sdp}
              });
              if(!result.ok)throw new Error(result.message||"Impossible de décrocher.");
              await flushLocalCandidates();
              callStatus.textContent="Connexion locale…";
            }catch(error){
              var id=currentCall&&currentCall.id;
              if(id)api("call/end","POST",{callId:id}).catch(function(){});
              var message=error&&error.message?error.message:"Impossible de décrocher.";
              closeCallUi();alert(message);
            }
          }

          async function rejectIncoming(){
            if(!incomingCall)return;
            var id=incomingCall.callId;
            incomingCall=null;
            stopCallTone();
            if(navigator.vibrate)navigator.vibrate(0);
            await api("call/reject","POST",{callId:id}).catch(function(){});
            closeCallUi();
          }

          async function endCurrentCall(notifyServer,message){
            var id=currentCall&&currentCall.id;
            if(notifyServer&&id){
              api("call/end","POST",{callId:id}).catch(function(){});
            }
            closeCallUi();
            if(message)alert(message);
          }

          function closeCallUi(){
            stopCallTone();
            if(navigator.vibrate)navigator.vibrate(0);
            if(pc){try{pc.ontrack=null;pc.onicecandidate=null;pc.close()}catch(_){}}
            pc=null;
            if(localStream){localStream.getTracks().forEach(function(track){try{track.stop()}catch(_){}})}
            localStream=null;
            remoteVideo.srcObject=null;localVideo.srcObject=null;remoteAudio.srcObject=null;
            currentCall=null;incomingCall=null;localCandidateQueue=[];remoteCandidateQueues={};
            muted=false;cameraEnabled=true;facingMode="user";
            callOverlay.hidden=true;incomingControls.hidden=true;activeControls.hidden=true;
          }

          async function handleCallEvent(event){
            if(event.type==="incoming"){
              showIncoming(event);return;
            }
            if(event.type==="ice"){
              if(currentCall&&currentCall.id===event.callId&&pc&&pc.remoteDescription){
                try{await pc.addIceCandidate(event.candidate)}catch(_){}
              }else{
                queueRemoteCandidate(event.callId,event.candidate);
              }
              return;
            }
            if(event.type==="answer"&&currentCall&&currentCall.id===event.callId&&pc){
              stopCallTone();
              try{
                await pc.setRemoteDescription(event.description);
                await flushRemoteCandidates(event.callId);
                callStatus.textContent="Connexion locale…";
              }catch(_){
                endCurrentCall(true,"La connexion de l'appel a échoué.");
              }
              return;
            }
            if((event.type==="rejected"||event.type==="ended"||event.type==="timeout")&&
               ((currentCall&&currentCall.id===event.callId)||(incomingCall&&incomingCall.callId===event.callId))){
              var message=event.type==="rejected"?"Appel refusé.":event.type==="timeout"?"Pas de réponse.":"Appel terminé.";
              closeCallUi();
              if(event.type!=="ended")setTimeout(function(){alert(message)},50);
            }
          }

          async function pollCalls(){
            if(callPollBusy)return;callPollBusy=true;
            try{
              var result=await api("call/poll?after="+encodeURIComponent(callCursor),"GET");
              if(result.ok){
                callCursor=Number(result.cursor||callCursor);
                for(var i=0;i<result.events.length;i++)await handleCallEvent(result.events[i]);
              }
            }catch(_){}
            callPollBusy=false;
          }

          async function toggleMute(){
            if(!localStream)return;muted=!muted;
            localStream.getAudioTracks().forEach(function(track){track.enabled=!muted});
            updateControlLabels();
          }

          async function toggleCamera(){
            if(!localStream||!currentCall||currentCall.kind!=="video")return;
            cameraEnabled=!cameraEnabled;
            localStream.getVideoTracks().forEach(function(track){track.enabled=cameraEnabled});
            updateControlLabels();
          }

          async function switchCamera(){
            if(!pc||!localStream||!currentCall||currentCall.kind!=="video")return;
            var next=facingMode==="user"?"environment":"user";
            try{
              var replacementStream=await navigator.mediaDevices.getUserMedia({
                audio:false,
                video:{facingMode:{ideal:next},width:{ideal:1280},height:{ideal:720}}
              });
              var newTrack=replacementStream.getVideoTracks()[0];
              if(!newTrack)return;
              newTrack.enabled=cameraEnabled;
              var sender=pc.getSenders().find(function(value){return value.track&&value.track.kind==="video"});
              if(sender)await sender.replaceTrack(newTrack);
              var oldTracks=localStream.getVideoTracks();
              oldTracks.forEach(function(track){localStream.removeTrack(track);track.stop()});
              localStream.addTrack(newTrack);
              localVideo.srcObject=null;localVideo.srcObject=localStream;
              facingMode=next;
              localVideo.style.transform=facingMode==="user"?"scaleX(-1)":"none";
            }catch(_){}
          }

          document.getElementById("audioCall").onclick=function(){startCall("audio")};
          document.getElementById("videoCall").onclick=function(){startCall("video")};
          document.getElementById("acceptCall").onclick=acceptIncoming;
          document.getElementById("rejectCall").onclick=rejectIncoming;
          document.getElementById("hangupCall").onclick=function(){endCurrentCall(true,"")};
          document.getElementById("muteCall").onclick=toggleMute;
          document.getElementById("cameraCall").onclick=toggleCamera;
          document.getElementById("flipCamera").onclick=switchCamera;

          window.addEventListener("beforeunload",function(){
            var id=currentCall&&currentCall.id?currentCall.id:(incomingCall&&incomingCall.callId?incomingCall.callId:"");
            if(!id)return;
            if(nativeBridgeAvailable()){
              try{window.ShizziNativeBridge.request("call/end","POST",JSON.stringify({callId:id}))}catch(_){}
            }else if(navigator.sendBeacon){
              navigator.sendBeacon("/chat/api/call/end",JSON.stringify({callId:id}));
            }
          });

          refresh(false);
          pollCalls();
          setInterval(function(){refresh(Boolean(selected))},2000);
          setInterval(pollCalls,750);
        })();
        </script>
        </body></html>
    """.trimIndent()
}
