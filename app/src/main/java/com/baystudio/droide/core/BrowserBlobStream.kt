package com.baystudio.droide.core

import kotlinx.serialization.json.JsonPrimitive

internal object BrowserBlobStream {
    const val CHUNK_BYTES = 48 * 1024

    fun script(address: String, marker: String) = """
        (() => {
          const marker=${JsonPrimitive(marker)}, address=${JsonPrimitive(address)};
          const timeout=setTimeout(()=>window.removeEventListener('message',connect),15000);
          function connect(event) {
            if(event.data!==marker||!event.ports.length) return;
            window.removeEventListener('message',connect);clearTimeout(timeout);
            const port=event.ports[0];let file,offset=0;
            port.onmessage=async event=>{
              if(event.data==='close'){file=null;port.close();return}
              if(event.data!=='next'||!file)return;
              try {
                const chunk=new Uint8Array(await file.slice(offset,offset+$CHUNK_BYTES).arrayBuffer());
                offset+=chunk.length;port.postMessage(btoa(String.fromCharCode(...chunk)));
                if(offset===file.size) file=null;
              } catch(_){port.postMessage('error');file=null}
            };
            fetch(address).then(r=>{if(!r.ok)throw Error();return r.blob()}).then(blob=>{
              file=blob;port.postMessage('size:'+blob.size)
            }).catch(()=>port.postMessage('error'));
          }
          window.addEventListener('message',connect);return true
        })()
    """.trimIndent()
}
