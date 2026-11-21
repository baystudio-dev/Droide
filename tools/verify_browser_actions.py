#!/usr/bin/env python3
"""Production DOM-script regression fixtures in isolated Chromium, not Android WebView certification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--chromium', default='/usr/bin/chromium')
    parser.add_argument('--temp-root', type=Path, default=Path('/dev/shm'))
    args = parser.parse_args()
    inputs = [ROOT / f'app/src/main/java/com/baystudio/droide/core/{name}.kt'
              for name in ['AgentBrowserDom', 'AgentBrowserInteraction', 'AgentBrowserSnapshot', 'BrowserBlobStream', 'AgentBrowserDrag']]
    inputs.append(ROOT / 'tools/web-verification/BrowserActionProbe.kt')
    cp = os.pathsep.join(str(p.resolve()) for p in sorted(args.jars.glob('*.jar')))
    cases = []
    with tempfile.TemporaryDirectory(prefix='droide-browser-actions-', dir=args.temp_root) as temporary:
        os.environ['TMPDIR'] = temporary
        classes = Path(temporary) / 'classes'
        subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '17', '-classpath', cp, '-d', str(classes), *map(str, inputs)], check=True)
        bridge = subprocess.Popen(['java', '-cp', str(classes) + os.pathsep + cp, 'com.baystudio.droide.core.BrowserActionProbe'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        def script(operation, target='', **options):
            bridge.stdin.write(json.dumps(dict(operation=operation, target=target, **options)) + '\n'); bridge.stdin.flush()
            return json.loads(bridge.stdout.readline())
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(executable_path=args.chromium, headless=True,
                    args=['--no-sandbox', '--disable-dev-shm-usage'], env={**os.environ, 'TMPDIR': temporary})
                context = browser.new_context(viewport={'width': 640, 'height': 480}, has_touch=True)
                context.route('**/*', lambda route: route.fulfill(status=200, content_type='text/html', body='<!doctype html><body></body>'))
                def page(body):
                    p = context.new_page(); p.goto('https://droide-fixture.test/page')
                    p.set_content('<!doctype html><html><body>' + body + '</body></html>'); return p
                def call(p, operation, target='#target', **options):
                    return json.loads(p.evaluate(script(operation, target, **options)))
                def tap(p, target='#target', **options):
                    result = call(p, 'check' if 'checked' in options else 'click', target, **options)
                    assert result['ok'], result
                    if result.get('already'): return result
                    assert result['prepared'], result
                    point = result['point']; size = p.viewport_size
                    p.touchscreen.tap(point['x'] * size['width'], point['y'] * size['height'])
                    return result
                def reject(name, html, operation, expected, observation, unchanged, **options):
                    p = page(html); result = call(p, operation, **options)
                    assert result['ok'] is False and result['code'] == expected, (name, result)
                    assert p.evaluate(observation) == unchanged, name
                    cases.append(name); p.close()
                rejects = [
                    ('disabled_click', '<button id="target" disabled onclick="window.count++">x</button>', 'click', 'disabled'),
                    ('hidden_click', '<button id="target" style="display:none" onclick="window.count++">x</button>', 'click', 'not_visible'),
                    ('covered_click', '<button id="target" onclick="window.count++">x</button><div style="position:fixed;inset:0;z-index:100">cover</div>', 'click', 'covered'),
                    ('ambiguous_selector', '<button class="target" onclick="window.count++">x</button><button class="target">y</button>', 'click', 'ambiguous_target'),
                    ('readonly_input', '<input id="target" readonly value="old">', 'type', 'readonly'),
                    ('disabled_input', '<input id="target" disabled value="old">', 'type', 'disabled'),
                    ('invalid_number', '<input id="target" type="number" value="7">', 'type', 'invalid_value'),
                    ('submit_not_typable', '<input id="target" type="submit" value="old">', 'type', 'not_typable'),
                    ('button_not_typable', '<button id="target" value="old">Button</button>', 'type', 'not_typable'),
                    ('password_guard', '<input id="target" type="password" value="old">', 'type', 'human_input_required'),
                    ('file_guard', '<input id="target" type="file">', 'type', 'human_input_required'),
                    ('maxlength_guard', '<input id="target" maxlength="2" value="old">', 'type', 'value_too_long'),
                ]
                for name, html, operation, code in rejects:
                    is_click = operation == 'click'; old = 0 if is_click else '7' if name == 'invalid_number' else '' if name == 'file_guard' else 'old'
                    reject(name, '<script>window.count=0</script>' + html, operation, code,
                           'window.count' if is_click else 'document.querySelector("#target").value', old,
                           target='.target' if name == 'ambiguous_selector' else '#target', text='invalid-number' if name == 'invalid_number' else 'changed')
                for name, html in [
                    ('tiny_html', '<button id="target" style="width:12px;height:12px;padding:0" onclick="count++">x</button>'),
                    ('tiny_svg', '<svg id="target" role="button" width="12" height="12" onclick="count++"><rect width="12" height="12" fill="blue"/></svg>'),
                    ('pointer_widget', '<div id="target" role="button" style="width:100px;height:40px" onpointerdown="count++">Pointer</div>'),
                    ('trusted_widget', '<button id="target" onclick="if(event.isTrusted)count++">Trusted</button>')]:
                    p = page('<script>window.count=0</script>' + html); tap(p); assert p.evaluate('count') == 1, name
                    cases.append(name); p.close()
                p = page('<div id="host"></div><script>window.count=0;host.attachShadow({mode:"open"}).innerHTML=`<label for="name">Your name</label><input id="name"><button id="target">Shadow</button>`;host.shadowRoot.querySelector("button").onclick=()=>count++</script>')
                snapshot = call(p, 'snapshot'); assert any(e['name'] == 'Your name' for e in snapshot['elements'])
                tap(p); assert p.evaluate('count') == 1; cases.append('shadow_discovery_and_click'); p.close()
                p = page('<button id="target">Original</button>')
                ref = call(p, 'snapshot')['elements'][0]['id']
                p.evaluate('(ref)=>target.outerHTML=`<button data-droide-agent-id="${ref}" id="target">Replacement</button>`', ref)
                result = call(p, 'click', ref); assert result['ok'] is False and result['code'] == 'stale_reference'
                cases.append('copied_attribute_cannot_retarget_reference'); p.close()
                p = page('<button id="target">First document</button>'); old_ref = call(p, 'snapshot')['elements'][0]['id']
                p.goto('https://droide-fixture.test/another'); p.set_content('<button id="target">New document</button>')
                new_ref = call(p, 'snapshot')['elements'][0]['id']; assert old_ref != new_ref
                assert call(p, 'click', old_ref)['code'] == 'stale_reference'
                cases.append('references_do_not_cross_documents'); p.close()
                user = page('<input id="draft"><div style="height:1800px"></div>')
                user.set_viewport_size({'width':390,'height':844})
                user.locator('#draft').fill('Keep this draft'); user.evaluate('scrollTo(0,240)')
                agent = page('<style>@media(max-width:700px){#desk{display:none}}@media(min-width:701px){#phone{display:none}}</style>'
                             '<input id="field"><button id="desk" onclick="window.layout=1">Wide menu</button>'
                             '<button id="phone" onclick="window.layout=2">Compact menu</button>')
                for width, height, button, label, layout in [(1280,800,'desk','Wide menu',1),(390,844,'phone','Compact menu',2),(1024,600,'desk','Wide menu',1)]:
                    agent.set_viewport_size({'width':width,'height':height})
                    observed = call(agent,'snapshot')
                    assert observed['viewport'] == {'width':width,'height':height}
                    assert label in [e['name'] for e in observed['elements']]
                    tap(agent,'#'+button); assert agent.evaluate('window.layout') == layout
                    if width == 1280: assert call(agent,'type','#field',text='Agent draft')['ok']
                    assert agent.locator('#field').input_value() == 'Agent draft'
                    assert user.locator('#draft').input_value() == 'Keep this draft' and user.evaluate('scrollY') == 240
                cases.append('independent_documents_and_responsive_controls_survive_live_resize'); agent.close(); user.close()
                p = page('<button id="target" disabled>Later</button><script>setTimeout(()=>target.disabled=false,200)</script>')
                deadline = time.monotonic() + 2
                while True:
                    result = call(p, 'click')
                    if result['ok']: break
                    assert result.get('retryable') and time.monotonic() < deadline; time.sleep(.07)
                cases.append('delayed_ready_preparation'); p.close()
                p = page('<style>body{margin:0;height:2200px}header{position:fixed;inset:0 0 auto;height:75px;background:white;z-index:2}button{position:absolute;top:780px}</style><header>Menu</header><button id="target" onclick="window.taps++">Continue</button>')
                p.evaluate('window.taps=0;scrollTo(0,760)')
                assert call(p, 'snapshot')['elements'][0]['pointer_state'] == 'covered'
                assert p.evaluate('scrollY') == 760
                tap(p)
                assert p.evaluate('window.taps') == 1
                cases.append('sticky_header_scrolls_to_reachable_target'); p.close()
                p = page('<button id="target" style="position:fixed;left:0;top:0;width:100px;height:40px" onclick="window.taps++">Go</button><div style="position:fixed;left:6px;right:0;top:0;height:40px;background:white" onclick="window.wrong++"></div>')
                p.evaluate('window.taps=0;window.wrong=0')
                tap(p)
                assert p.evaluate('[taps,wrong]') == [1,0]
                cases.append('partially_covered_button_receives_one_native_tap'); p.close()
                p = page('<button id="target" onclick="window.taps++">Save</button>' + '<button>Other</button>'*55 +
                         '<dialog aria-label="Preferences"><input value="PRIVATE-FIELD"><button id="close" onclick="this.parentElement.close()">Close</button></dialog>')
                p.evaluate('window.taps=0;document.querySelector("dialog").showModal()')
                observed = call(p, 'snapshot')
                assert observed['elements'][0]['pointer_state'] == 'covered'
                denial = call(p, 'click')
                assert denial['code'] == 'covered' and p.evaluate('taps') == 0
                layer = denial['blocked_by']
                assert layer['tag'] == 'dialog' and layer['name'] == 'Preferences'
                assert 'PRIVATE-FIELD' not in json.dumps([observed, denial])
                close = next(c['id'] for c in layer['controls'] if c['name'] == 'Close')
                tap(p, close); tap(p)
                assert p.evaluate('taps') == 1
                cases.append('modal_identifies_reachable_controls_beyond_element_page'); p.close()
                p = page('<style>body{height:1900px}#target{position:absolute;top:540px}</style><button id="target" onclick="window.taps++">Save</button>'
                         '<div aria-label="Cookie preferences" style="position:fixed;inset:0;background:white"><button onclick="this.parentElement.remove()">Dismiss</button></div>')
                p.evaluate('window.taps=0;scrollTo(0,500)')
                denial = call(p,'click')
                assert denial['code'] == 'covered' and denial['blocked_by']['name'] == 'Cookie preferences'
                assert p.evaluate('[scrollY,taps]') == [500,0]
                dismiss = next(c['id'] for c in denial['blocked_by']['controls'] if c['name']=='Dismiss')
                tap(p,dismiss); tap(p); assert p.evaluate('taps') == 1
                cases.append('full_screen_fixed_banner_returns_controls_without_scrolling_behind_it'); p.close()
                p = page('<label for="target">Accept</label><input id="target" type="checkbox"><script>window.count=0;target.onchange=()=>count++</script>')
                tap(p, checked=True); assert tap(p, checked=True)['already']
                assert p.evaluate('target.checked && count===1'); assert call(p, 'snapshot')['elements'][0]['checked'] is True
                cases.append('checkbox_idempotent_and_observable'); p.close()
                p = page('<style>html,body{height:100%;overflow:hidden}</style><div id="target" style="width:200px;height:100px;overflow:auto"><div style="width:1500px;height:1500px">Long</div></div>')
                assert call(p, 'scroll', text='down')['moved']; assert call(p, 'scroll', text='right')['moved']
                assert p.evaluate('target.scrollTop>0&&target.scrollLeft>0'); assert call(p, 'snapshot')['elements'][0]['scrollable']
                cases.append('nested_horizontal_vertical_scroll'); p.close()
                p = page('<input id="target"><script>window.injected=false</script>')
                value = '\");window.injected=true;// 🚀'
                assert call(p, 'type', text=value)['ok']; assert p.evaluate('target.value') == value and not p.evaluate('injected')
                assert call(p, 'type', text='')['ok'] and p.evaluate('target.value') == ''
                cases.append('escaped_unicode_and_empty_fill'); p.close()
                p = page('<input id="target" value="old" oninput="this.value=\'old\'">')
                assert call(p, 'type', text='new')['code'] == 'value_not_retained'; cases.append('controlled_field_rejection_reported'); p.close()
                p = page('<label for="target">City</label><select id="target"><option value="secret-a">Jakarta</option><option value="secret-b">Bandung</option></select>')
                snapshot = call(p, 'snapshot'); field = snapshot['elements'][0]
                assert field['name'] == 'City' and field['options'][1]['label'] == 'Bandung' and 'secret-b' not in json.dumps(snapshot)
                assert call(p, 'type', option_index=1)['ok'] and p.evaluate('target.value') == 'secret-b'
                cases.append('select_discovery_and_index'); p.close()
                p = page('<iframe srcdoc="<button>Frame</button>"></iframe>')
                snapshot = call(p, 'snapshot'); assert snapshot['source_incomplete'] and snapshot['frames'][0]['available'] is False
                cases.append('frame_coverage_explicit'); p.close()
                p = page('<form id="form"><label for="name">Name</label><input id="name" name="name" required><label for="email">Email</label><input id="email" name="email" type="email" required><select id="city" name="city"><option value="jkt">Jakarta</option><option value="bdg">Bandung</option></select><textarea id="note" name="note"></textarea><input id="terms" name="terms" type="checkbox" required><input id="regular" type="radio" name="shipping" value="regular" checked><input id="express" type="radio" name="shipping" value="express"><button id="send">Send</button></form><script>window.sent=[];form.onsubmit=e=>{e.preventDefault();sent.push(Object.fromEntries(new FormData(form)))}</script>')
                fields = call(p, 'snapshot')['elements']; name_ref = next(e['id'] for e in fields if e['name'] == 'Name')
                for target, value in [(name_ref, 'Nadia'), ('#email', 'nadia@example.test'), ('#note', 'Delivery note')]:
                    assert call(p, 'type', target, text=value)['ok']
                assert call(p, 'type', '#city', option_index=1)['ok']
                tap(p, '#terms', checked=True); tap(p, '#express', checked=True); tap(p, '#send')
                assert p.evaluate('sent') == [{'name':'Nadia','email':'nadia@example.test','city':'bdg','note':'Delivery note','terms':'on','shipping':'express'}]
                cases.append('form_discovery_fill_and_single_submission'); p.close()
                for size in [{'width':390,'height':844}, {'width':1280,'height':800}]:
                    p = page('<style>*{box-sizing:border-box}html,body{overflow:hidden;margin:0}main{height:100vh;overflow-y:auto;scroll-snap-type:y mandatory}article{height:100vh;scroll-snap-align:start}video{width:80%;height:65%}</style>'
                             '<main>'+'<article><video controls muted playsinline></video></article>'*3+'</main>')
                    p.set_viewport_size(size)
                    snapshot = call(p, 'snapshot')
                    videos = [e for e in snapshot['elements'] if e['tag']=='video']
                    assert len(videos)==3 and videos[0]['media']['paused'] and videos[0]['media']['controls']
                    for offset in (1,2):
                        result = call(p,'scroll','',text='down')
                        assert result['moved'] and result['after']['y']==offset*size['height'] and p.evaluate('scrollY')==0, result
                    assert not call(p,'scroll','',text='down')['moved']
                    assert call(p,'scroll','',text='up')['after']['y']==size['height']
                    cases.append('vertical_video_snap_feed_'+str(size['width']));p.close()
                p = page('<style>body{height:3000px}#scroller{height:100vh;overflow:auto}</style><main id="scroller"><div style="height:3500px"></div></main><dialog>Confirm</dialog>')
                p.evaluate('document.querySelector("dialog").showModal()')
                stopped=call(p,'scroll','',text='down')
                assert not stopped['moved'] and stopped['blocked_by']['tag']=='dialog' and p.evaluate('scroller.scrollTop+scrollY')==0
                cases.append('modal_keeps_background_feed_still');p.close()
                p = page('<style>*{margin:0}html,body{height:100%;overflow:hidden}main{overflow:auto;height:100vh}aside{position:fixed;top:0;bottom:0;left:48%;width:4%;background:white}</style><main><div style="height:300vh">feed</div></main><aside></aside>')
                assert call(p,'scroll','',text='down')['moved'] and p.evaluate('document.querySelector("main").scrollTop>0&&scrollY===0')
                cases.append('reachable_feed_beside_a_narrow_fixed_overlay');p.close()
                p = page('<video id="clip" style="width:240px;height:160px" playsinline muted loop></video><button id="target" onclick="clip.play()">Play</button>')
                p.evaluate("""async () => {
                    const canvas=document.createElement('canvas'); canvas.width=64;canvas.height=48;
                    const g=canvas.getContext('2d'), stream=canvas.captureStream(15);
                    const recorder=new MediaRecorder(stream,{mimeType:'video/webm'}),chunks=[];
                    const done=new Promise(resolve=>{recorder.ondataavailable=e=>chunks.push(e.data);recorder.onstop=resolve});
                    recorder.start();for(let i=0;i<10;i++){g.fillStyle=i%2?'red':'blue';g.fillRect(0,0,64,48);await new Promise(r=>setTimeout(r,65))}
                    recorder.stop();await done;stream.getTracks().forEach(t=>t.stop());
                    clip.src=URL.createObjectURL(new Blob(chunks,{type:'video/webm'}));
                }""")
                tap(p);p.wait_for_function('clip.videoWidth===64&&clip.currentTime>0')
                media=next(e for e in call(p,'snapshot')['elements'] if e['tag']=='video')
                assert media['media']['ready_state']>=2 and not media['media']['paused'] and 'blob:' not in json.dumps(media)
                cases.append('native_tap_playback_and_media_state_without_source_url');p.close()
                p = page('<p>Download</p>')
                address = p.evaluate("""() => {const b=new Uint8Array(110003);for(let i=0;i<b.length;i++)b[i]=i%251;return URL.createObjectURL(new Blob([b]))}""")
                assert p.evaluate(script('blob_stream',address,text='download-fixture')) is True
                ack=p.evaluate("""async () => {
                    const c=new MessageChannel(), result=[];
                    const done=new Promise((resolve,reject)=>{const stop=setTimeout(()=>reject('timeout'),4000);let offset=0;
                      c.port1.onmessage=e=>{if(e.data.startsWith('size:')) {result.push(e.data)}
                        else {const s=atob(e.data);for(let i=0;i<s.length;i++)if(s.charCodeAt(i)!==(offset+i)%251){reject('bytes');return}
                          offset+=s.length;result.push(s.length)}
                        if(offset===110003){clearTimeout(stop);c.port1.postMessage('close');resolve(result)}
                        else c.port1.postMessage('next')};
                    });window.postMessage('download-fixture',location.origin,[c.port2]);return await done}""")
                assert ack==['size:110003',49152,49152,11699], ack
                cases.append('generated_blob_download_verified_across_three_bounded_chunks');p.close()
                p = page('<style>body{margin:0;height:2000px}main{height:160px;overflow:auto}section{height:90px;overflow:auto}</style>'
                         '<main><section><article style="height:500px"><button id="target">Video</button></article></section><div style="height:600px"></div></main>')
                assert call(p, 'scroll', '#target', text='down')['moved']
                assert p.evaluate('document.querySelector("section").scrollTop>0 && document.querySelector("main").scrollTop===0 && scrollY===0')
                p.evaluate('document.querySelector("section").scrollTop=1000')
                assert not call(p, 'scroll', 'section', text='down')['moved']
                assert p.evaluate('document.querySelector("main").scrollTop===0 && scrollY===0')
                cases.append('scroll_from_child_uses_nearest_pane_and_explicit_boundary'); p.close()

                p = page('<style>body{margin:0;overflow:hidden}main{width:100vw;height:100vh;touch-action:none}</style>'
                         '<main></main><script>window.moves=0;document.querySelector("main").ontouchmove=e=>moves++</script>')
                fixed = call(p, 'scroll', '', text='down')
                assert not fixed['moved'] and fixed['next_action']=={'operation':'swipe','text':'up'}
                ready = call(p, 'swipe', '', text='up')
                assert ready['ok'] and call(p,'point',ready['target'],**ready['point'])['ok']
                touch = p.context.new_cdp_session(p)
                for op,y in [('touchStart',360),('touchMove',310),('touchMove',230),('touchMove',130),('touchEnd',0)]:
                    touch.send('Input.dispatchTouchEvent',{'type':op,'touchPoints':[{'x':320,'y':y}] if op!='touchEnd' else []})
                assert p.evaluate('moves')>0
                cases.append('targetless_swipe_on_gesture_driven_feed'); p.close()

                p=page('<style>body{margin:0;overflow:hidden}article,video{width:100vw;height:100vh;display:block}</style>'
                       '<article><video id="clip"></video></article>')
                ready=call(p,'swipe','',text='up');ref=ready['target']
                def position():return call(p,'swipe_position',ref,text='up')['position']
                p.evaluate("clip.src='/first.mp4'")
                first=position()
                p.evaluate("clip.src='/second.mp4'")
                second=position()
                assert first['x']==second['x'] and first['y']==second['y'] and first['item']!=second['item']
                assert position()==second and '.mp4' not in json.dumps(second)
                cases.append('recycled_feed_video_advances_without_scroll_offset_or_url_disclosure');p.close()

                p = page('<button id="target" style="position:absolute;left:20px;top:40px;width:6px;height:6px;padding:0;border:0"></button>')
                small = call(p,'click');p.locator('#target').evaluate("e=>e.style.left='21px'")
                assert call(p,'point',small['target'],**small['point'])['ok']
                p.evaluate("target.outerHTML=target.outerHTML")
                assert not call(p,'point',small['target'],**small['point'])['ok']
                cases.append('six_pixel_target_survives_small_motion_and_rejects_replacement');p.close()

                for allow in (True, False):
                    p = page('<div draggable="true" id="source" style="position:absolute;left:10px;top:10px;width:60px;height:60px">Card</div>'
                             '<div id="dest" style="position:absolute;left:140px;top:10px;width:90px;height:90px">Drop</div>'
                             '<script>window.log=[];source.ondragstart=e=>{e.dataTransfer.setData("text/plain","card");log.push("start")};'
                             'source.ondragend=e=>log.push("end");dest.ondrop=e=>{e.preventDefault();log.push(e.dataTransfer.getData("text/plain"))}</script>')
                    p.locator('#dest').evaluate('(e,allow)=>{e.ondragover=evt=>{if(allow)evt.preventDefault()}}',allow)
                    source=call(p,'drag','#source');dest=call(p,'drag','#dest')
                    def phase(op, at):
                        return call(p,'drag_phase',source['target'],phase=op,destination=dest['target'],html=True,**at)
                    assert phase('start',source['point'])['ok'];assert phase('move',dest['point'])['ok']
                    end=phase('end',dest['point'])
                    assert end['ok']==allow and end['dispatched']
                    assert p.evaluate('log')==(['start','card','end'] if allow else ['start','end'])
                    cases.append('html_drop_transfers_once' if allow else 'refused_drop_reports_outcome_without_replay');p.close()

                p=page('<div id="target" style="position:absolute;left:80px;top:60px;width:12px;height:12px"></div>'
                       '<script>window.log=[];for(const n of ["pointerdown","pointermove","pointerup","click"]) target.addEventListener(n,e=>log.push([n,e.clientX]))</script>')
                knob=call(p,'drag');start=knob['point'];end={**start,'x':start['x']+3/640}
                for op,at in [('start',start),('move',end),('end',end)]:
                    assert call(p,'drag_phase',knob['target'],phase=op,html=False,**at)['ok']
                events=p.evaluate('log')
                assert [e[0] for e in events]==['pointerdown','pointermove','pointerup'] and events[-1][1]-events[0][1]==3,events
                cases.append('three_css_pixel_slider_motion_without_click');p.close()

                p=page('<button id="target" style="touch-action:manipulation" onclick="clicks++" ondblclick="doubles++">Twice</button>'
                       '<script>window.clicks=0;window.doubles=0</script>')
                ready=call(p,'double_click');ref=ready['target']
                assert call(p,'double_watch',ref,start=True)['ok']
                x,y=ready['point']['x']*640,ready['point']['y']*480
                p.touchscreen.tap(x,y);p.wait_for_timeout(65);p.touchscreen.tap(x,y);p.wait_for_timeout(340)
                assert call(p,'double_watch',ref,start=False)['ok']
                assert p.evaluate('[clicks,doubles]')==[2,1]
                cases.append('double_click_delivers_two_taps_and_one_dblclick');p.close()

                p=page('<button id="target" style="touch-action:none">Hold</button><script>window.press=[];'
                       'for(const n of ["pointerdown","pointerup"])target.addEventListener(n,e=>press.push([n,e.timeStamp]))</script>')
                hold=call(p,'hold');x,y=hold['point']['x']*640,hold['point']['y']*480
                cdp=p.context.new_cdp_session(p);cdp.send('Input.dispatchTouchEvent',{'type':'touchStart','touchPoints':[{'x':x,'y':y}]})
                p.wait_for_timeout(550);cdp.send('Input.dispatchTouchEvent',{'type':'touchEnd','touchPoints':[]})
                presses=p.evaluate('press')
                assert [e[0] for e in presses]==['pointerdown','pointerup'] and presses[1][1]-presses[0][1]>=500
                cases.append('long_press_followed_by_one_release');p.close()
                proof = {'status':'PASS','cases':cases,'count':len(cases),'chromium':browser.version,
                         'productionDomScripts':True,'inputDriver':'Playwright touch on prepared production coordinates',
                         'androidWebViewTested':False,'network':'All requests intercepted locally; no external forms submitted.',
                         'productionInputs':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}}
                browser.close()
        finally:
            bridge.stdin.close()
            try: bridge.wait(timeout=5)
            except subprocess.TimeoutExpired: bridge.kill(); bridge.wait()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, indent=2) + '\n'); print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
