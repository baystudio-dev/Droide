package com.baystudio.droide.core

import kotlinx.serialization.json.JsonPrimitive

/** Page-local mouse and HTML drag events. The transfer belongs to this gesture and never leaves the page. */
internal object AgentBrowserDrag {
    fun script(id: String, phase: String, source: String, destination: String?, html: Boolean, x: Float, y: Float): String {
        require(phase in setOf("start", "move", "end", "cancel"))
        require(x in 0f..1f && y in 0f..1f)
        return """
            (() => { try { ${AgentBrowserDom.runtime}
              const token=${JsonPrimitive(id)}, phase=${JsonPrimitive(phase)}, html=$html, v=window.visualViewport;
              const x=(v?.offsetLeft||0)+$x*(v?.width||innerWidth), y=(v?.offsetTop||0)+$y*(v?.height||innerHeight);
              const hit=D.hit(x,y), src=D.resolve(${JsonPrimitive(source)}).el, dest=${destination?.let { "D.resolve(${JsonPrimitive(it)}).el" } ?: "null"};
              const response=(ok,code,dispatched)=>JSON.stringify({ok,action:'drag',method:html?'html-drag':'pointer-drag',dispatched,...(code?{code}:{})});
              let g=D.activeDrag;
              const event=(node,type,Ctor,extra={})=>node.dispatchEvent(new Ctor(type,{bubbles:true,cancelable:true,composed:true,
                view:window,clientX:x,clientY:y,button:0,buttons:['mouseup','pointerup','pointercancel','dragend','drop'].includes(type)?0:1,...extra}));
              const pointer=(node,type)=>event(node,type,PointerEvent,{pointerId:1,pointerType:'mouse',isPrimary:true});
              const drag=(node,type)=>event(node,type,DragEvent,{dataTransfer:g.transfer});
              function finish() {
                delete D.activeDrag;
                if(html) drag(g.src,'dragend'); else pointer(g.src,'pointercancel');
              }
              if(phase==='start') {
                if(!src||D.disabled(src)||!D.contains(src,hit)||(html&&!src.draggable)) return response(false,'target_changed',false);
                g={token,src,last:null,transfer:html?new DataTransfer():null}; D.activeDrag=g;
                if(html) {
                  if(!drag(src,'dragstart')) {delete D.activeDrag;return response(false,'drag_start_cancelled',true);}
                } else if(pointer(src,'pointerdown')) event(src,'mousedown',MouseEvent);
                return response(true,null,true);
              }
              if(!g||g.token!==token) return response(false,'drag_interrupted',true);
              if(phase==='cancel') {finish();return response(false,'drag_cancelled',true);}
              if(!src||src!==g.src||!hit||D.disabled(src)) {finish();return response(false,'target_changed',true);}
              if(phase==='end'&&${destination != null}&&(!dest||D.disabled(dest)||!D.contains(dest,hit))) {
                finish();return response(false,'drop_target_changed',true);
              }
              if(html) {
                if(hit!==g.last) {
                  if(g.last) drag(g.last,'dragleave');drag(hit,'dragenter');g.last=hit;
                }
                const accepts=!drag(hit,'dragover');
                if(phase==='end') {
                  if(!accepts||!hit.isConnected||D.hit(x,y)!==hit) {finish();return response(false,'drop_not_accepted',true);}
                  drag(hit,'drop');finish();
                }
              } else {
                if(phase==='end') {delete D.activeDrag;pointer(hit,'pointerup');event(hit,'mouseup',MouseEvent);}
                else {pointer(hit,'pointermove');event(hit,'mousemove',MouseEvent);}
              }
              return response(true,null,true);
            } catch(_) {return JSON.stringify({ok:false,action:'drag',code:'drag_event_unavailable',dispatched:true});} })()
        """.trimIndent()
    }
}
