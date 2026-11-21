package com.baystudio.droide.core

/** Shared bounded DOM helpers. References identify observed nodes, never copied HTML attributes. */
internal object AgentBrowserDom {
    val runtime = """
        const D = (() => {
          if (window.__droideBrowserV3) return window.__droideBrowserV3;
          const ids=new WeakMap(), nodes=new Map(), mediaIds=new WeakMap(); let sequence=0;
          const documentId=Array.from(crypto.getRandomValues(new Uint32Array(2))).map(n=>n.toString(16)).join('');
          const attr=(e,k,n=256)=>String(e.getAttribute?.(k)||'').slice(0,n);
          function each(root,visit,max=20000) {
            const roots=[root]; let count=0;
            for(let i=0;i<roots.length&&i<256;i++) {
              const walker=document.createTreeWalker(roots[i],NodeFilter.SHOW_ELEMENT);
              let e=roots[i].nodeType===1?roots[i]:walker.nextNode();
              for(;e;e=walker.nextNode()) {
                if(++count>max) return false;
                if(visit(e)===false) return true;
                if(e.shadowRoot) roots.push(e.shadowRoot);
              }
            }
            return roots.length<=256;
          }
          function text(e,max=160,budget=null) {
            let out='',seen=0;
            function walk(n,depth) {
              if(++seen>512||depth>32||out.length>=max||budget&&budget.remaining<=0) return;
              if(budget) budget.remaining--;
              if(n.nodeType===3) {out+=(n.nodeValue||'').slice(0,max-out.length);return;}
              if(n.nodeType!==1||['INPUT','TEXTAREA','SELECT','SCRIPT','STYLE'].includes(n.tagName)||n.isContentEditable||
                ['textbox','searchbox'].includes(attr(n,'role',40).toLowerCase())||
                n.hasAttribute('contenteditable')&&['','true','plaintext-only'].includes(attr(n,'contenteditable',32).toLowerCase())) return;
              for(let c=n.firstChild;c&&seen<=512&&out.length<max;c=c.nextSibling) walk(c,depth+1);
            }
            walk(e,0);return out.replace(/\s+/g,' ').trim();
          }
          function name(e,budget=null) {
            const root=e.getRootNode(), refs=attr(e,'aria-labelledby').split(/\s+/).slice(0,8);
            const labelled=refs.map(id=>root.getElementById?.(id)).filter(Boolean).map(x=>text(x,100,budget)).join(' ').trim();
            if(labelled) return labelled.slice(0,160);
            if(attr(e,'aria-label')) return attr(e,'aria-label',160);
            if(e.labels?.length) return Array.from({length:Math.min(e.labels.length,4)},(_,i)=>text(e.labels[i],100,budget)).join(' ').slice(0,160);
            return attr(e,'alt',160)||attr(e,'title',160)||attr(e,'placeholder',160)||text(e,160,budget)||attr(e,'name',160);
          }
          function ref(e) {
            let id=ids.get(e); if(id&&nodes.has(id)) return id;
            if(nodes.size>=10000) for(const [key,node] of nodes) if(!node.isConnected) nodes.delete(key);
            if(nodes.size>=10000) throw new Error('element reference budget reached; reload before continuing');
            id='d'+documentId+'_'+(++sequence);ids.set(e,id);nodes.set(id,e);return id;
          }
          function resolve(target) {
            if(/^d(?:[0-9]+|[0-9a-f]+_[0-9]+)${'$'}/.test(target)) {
              const e=nodes.get(target);
              return e?.isConnected?{el:e}:{error:'stale_reference',message:'Read the page again; this element reference is no longer attached.'};
            }
            const matches=[];
            try {
              document.documentElement.matches(target);
              const complete=each(document,e=>{if(e.matches(target)) matches.push(e);return matches.length<2;});
              if(!complete) return {error:'target_search_limit',message:'Read the page and use its element reference.'};
            } catch(_) {return {error:'invalid_selector',message:'Use a valid CSS selector or an observed element reference.'};}
            if(matches.length>1) return {error:'ambiguous_target',message:'The selector matches multiple elements. Use a unique reference.'};
            return matches.length?{el:matches[0]}:{error:'target_not_found',message:'Target is not present.',retryable:true};
          }
          function parent(e) {return e.parentElement||e.getRootNode()?.host;}
          function disabled(e) {
            if(e.matches(':disabled')) return true;
            for(let p=e,depth=0;p&&depth++<256;p=parent(p))
              if(p.hasAttribute('inert')||attr(p,'aria-disabled')==='true') return true;
            return false;
          }
          function hit(x,y) {
            let e=document.elementFromPoint(x,y);
            for(let i=0;i<32&&e?.shadowRoot;i++) {
              const inner=e.shadowRoot.elementFromPoint?.(x,y);if(!inner||inner===e) break;e=inner;
            }
            return e;
          }
          function scrollSurface(direction,start=null) {
            const v=window.visualViewport,w=v?.width||innerWidth,h=v?.height||innerHeight;
            const horizontal=direction==='left'||direction==='right', root=document.scrollingElement;
            const forward=['right','down','bottom'].includes(direction);
            function nearest(node) {
              let boundary=null;
              for(let e=node,n=0;e&&n++<128;e=parent(e)) {
                const s=getComputedStyle(e),overflow=horizontal?s.overflowX:s.overflowY;
                const range=horizontal?e.scrollWidth-e.clientWidth:e.scrollHeight-e.clientHeight;
                if(range>0&&(['auto','scroll','overlay'].includes(overflow)||e===root&&!['hidden','clip'].includes(overflow))) {
                  const pos=horizontal?e.scrollLeft:e.scrollTop;
                  const min=horizontal&&s.direction==='rtl'?-range:0;
                  if((forward?min+range-pos:pos-min)>.5||e===start) return e;
                  boundary=boundary||e;
                  if(['contain','none'].includes(horizontal?s.overscrollBehaviorX:s.overscrollBehaviorY)) return e;
                }
                if(e.matches('dialog[open],[role="dialog"],[aria-modal="true"],[role="alertdialog"]')) return boundary||e;
              }
              return boundary;
            }
            if(start) return nearest(start)||root;
            for(const [x,y] of [[.5,.5],[.5,.3],[.5,.7],[.35,.5],[.65,.5]]) {
              const found=nearest(hit((v?.offsetLeft||0)+x*w,(v?.offsetTop||0)+y*h));
              if(found) return found;
            }
            return root;
          }
          function visibleFeedItem(pane) {
            const v=window.visualViewport,x=v?.offsetLeft||0,y=v?.offsetTop||0,w=v?.width||innerWidth,h=v?.height||innerHeight;
            const r=pane.getBoundingClientRect(),left=Math.max(x,r.left),right=Math.min(x+w,r.right);
            const top=Math.max(y,r.top),bottom=Math.min(y+h,r.bottom);
            if(right<=left||bottom<=top) return null;
            let node=hit((left+right)/2,(top+bottom)/2);
            if(!contains(pane,node)) return null;
            for(let depth=0;node&&depth++<64;node=parent(node)) {
              if(node.matches('video,article,li,[role="listitem"],[role="option"],[role="tabpanel"]')) {
                const video=node.localName==='video'?node:node.querySelector('video');
                if(!video) return ref(node);
                const src=video.getAttribute('src')||video.currentSrc||video.querySelector('source')?.getAttribute('src')||'';
                const prior=mediaIds.get(video), serial=(prior?.serial||0)+(prior?.src&&src&&prior.src!==src?1:0);
                mediaIds.set(video,{src:src||prior?.src||'',serial});
                return ref(node)+'/'+ref(video)+'/'+serial;
              }
              if(node===pane) break;
            }
            return null;
          }
          function contains(e,other) {
            for(let p=other,i=0;p&&i++<256;p=parent(p)) if(p===e) return true;
            return false;
          }
          function layerFor(hit,target,fixed=false) {
            for(let e=hit,n=0;e&&e!==document.body&&e!==document.documentElement&&n++<32;e=parent(e)) {
              if(target&&contains(e,target)) break;
              if(fixed) {
                const position=getComputedStyle(e).position;
                if(position==='fixed'||position==='sticky') return e;
              } else if(e.matches('dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"],[popover]')) return e;
            }
            return null;
          }
          function blocker(hit,target) {
            if(!hit) return null;
            const layer=layerFor(hit,target)||layerFor(hit,target,true),owner=layer||hit;
            const info={tag:owner.localName,role:attr(owner,'role',40),name:attr(owner,'aria-label',100)||attr(owner,'title',100)};
            if(layer) {
              const controls=[],budget={remaining:256};
              each(layer,node=>{
                if(node.matches('button,a[href],input[type="button"],[role="button"],[role="link"]')&&geometry(node,false).point) {
                  controls.push({id:ref(node),name:name(node,budget).slice(0,80),tag:node.localName});
                }
                return controls.length<4;
              },128);
              info.controls=controls;
            }
            return info;
          }
          function geometry(e,scroll=true,s=getComputedStyle(e)) {
            if(!e.isConnected||s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse')
              return {error:'not_visible',message:'Target is not visible.',retryable:true};
            if(disabled(e)) return {error:'disabled',message:'Target is disabled or inert.',retryable:true};
            let r=e.getBoundingClientRect();
            const vv=window.visualViewport,w=vv?.width||innerWidth,h=vv?.height||innerHeight,ox=vv?.offsetLeft||0,oy=vv?.offsetTop||0;
            if(scroll&&(r.bottom<=oy||r.top>=oy+h||r.right<=ox||r.left>=ox+w)) {
              e.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});r=e.getBoundingClientRect();
            }
            let interceptor=null,inViewport=false;
            const rects=e.getClientRects(), boxes=rects.length>1?
              Array.from({length:Math.min(8,rects.length)},(_,i)=>rects[Math.round(i*(rects.length-1)/(Math.min(8,rects.length)-1))]):[r];
            if(rects.length>1) boxes.push(r);
            for(const box of boxes) {
              const left=Math.max(ox,box.left),right=Math.min(ox+w,box.right),top=Math.max(oy,box.top),bottom=Math.min(oy+h,box.bottom);
              if(right<=left||bottom<=top) continue;
              inViewport=true;
              const dx=right-left,dy=bottom-top,edgeX=Math.min(.5,2/dx),edgeY=Math.min(.5,2/dy);
              for(const [fx,fy] of [[.5,.5],[.2,.2],[.8,.2],[.2,.8],[.8,.8],[edgeX,.5],[1-edgeX,.5],[.5,edgeY],[.5,1-edgeY]]) {
                const x=left+dx*fx,y=top+dy*fy,front=hit(x,y);
                if(contains(e,front)) return {point:{x:(x-ox)/w,y:(y-oy)/h},
                  bounds:{left:(left-ox)/w,top:(top-oy)/h,right:(right-ox)/w,bottom:(bottom-oy)/h},
                  viewport:{width:w,height:h},ref:ref(e)};
                interceptor=interceptor||front;
              }
            }
            if(scroll&&!layerFor(interceptor,e)) {
              const layer=layerFor(interceptor,e,true)?.getBoundingClientRect();
              if(!layer||layer.left>ox+1||layer.top>oy+1||layer.right<ox+w-1||layer.bottom<oy+h-1) {
                e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});
                return geometry(e,false);
              }
            }
            return inViewport?{error:'covered',message:'Another element intercepts the target.',retryable:true,interceptor}:
              {error:'outside_viewport',message:'Target has no visible area.',retryable:true};
          }
          const api={each,text,name,ref,resolve,disabled,geometry,attr,contains,scrollSurface,visibleFeedItem,hit,blocker};
          Object.defineProperty(window,'__droideBrowserV3',{value:api,configurable:true});return api;
        })();
    """.trimIndent()
}
