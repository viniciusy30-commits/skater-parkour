import sys,json
from playwright.sync_api import sync_playwright
views=json.loads(sys.argv[2]);pref=sys.argv[1]
with sync_playwright() as p:
    b=p.chromium.launch(args=['--use-angle=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist','--use-gl=angle']);pg=b.new_page(viewport={'width':900,'height':900})
    pg.goto('file:///home/claude/b/prev/prev.html');pg.wait_for_function('typeof draw==="function"');pg.wait_for_timeout(2500)
    print(pg.title())
    for n,(yaw,pitch,dist,tg) in views.items():
        pg.evaluate(f'draw({yaw},{pitch},{dist},{json.dumps(tg)})');pg.locator('canvas').screenshot(path=f'/home/claude/b/prev/{pref}_{n}.png')
    b.close()
