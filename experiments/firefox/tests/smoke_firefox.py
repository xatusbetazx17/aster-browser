"""Install the real unsigned add-on temporarily into an isolated Firefox profile.

Exercises real extension storage, tab navigation, page extraction,
backup validation and the responsive workspace UI.
Never signs in to a service or claims to validate subscription DRM playback.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sys
import threading

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.firefox.options import Options
from selenium.webdriver.firefox.service import Service
from selenium.webdriver.support.ui import WebDriverWait

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build import build

ID = "aster-companion@aster-browser.local"
UUID = "785c5d87-4c96-4338-b86c-5be5e4590d12"
EXT = f"moz-extension://{UUID}/"


class Fixture(BaseHTTPRequestHandler):
    paths = []

    def do_GET(self):
        type(self).paths.append(self.path)
        if self.path.startswith("/ad.js"):
            data = b"document.documentElement.dataset.adLoaded='yes';"
            mime = "application/javascript"
        else:
            data = b'''<!doctype html><title>Reading fixture</title><article><h1>Aster test article</h1>
            <p>Aster workspaces keep saved browser tabs together for later reading and research.</p>
            <p>Parked pages wait until the reader chooses to resume the original website.</p>
            <form><input value="DO-NOT-EXTRACT"><textarea>PRIVATE-FORM-TEXT</textarea></form>
            </article><script src="/ad.js"></script>'''
            mime = "text/html; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


def main():
    xpi = build()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    origin = f"http://127.0.0.1:{server.server_port}"
    opts = Options()
    opts.add_argument("-headless")
    if os.environ.get("FIREFOX_BINARY"):
        opts.binary_location = os.environ["FIREFOX_BINARY"]
    opts.set_preference("extensions.webextensions.uuids", json.dumps({ID: UUID}))
    # Mozilla requires explicit browser-UI automation access for extension
    # documents. Only the isolated test driver receives this flag.
    driver = webdriver.Firefox(options=opts, service=Service(service_args=["--allow-system-access"]))
    driver.set_script_timeout(20)
    wait = WebDriverWait(driver, 20)
    try:
        driver.install_addon(str(xpi), temporary=True)
        driver.get(EXT + "dashboard.html")
        wait.until(lambda d: "Firefox platform:" in d.find_element(By.ID, "platform").text)
        dashboard = driver.current_window_handle

        def call(kind, **rest):
            result = driver.execute_async_script("const done=arguments[arguments.length-1]; browser.runtime.sendMessage(arguments[0]).then(done,e=>done({ok:false,error:e.message}));", {"type": kind, **rest})
            assert result["ok"], result
            return result.get("value")

        def api(expression):
            result = driver.execute_async_script("const done=arguments[arguments.length-1]; (async()=>{" + expression + "})().then(v=>done({ok:true,value:v}),e=>done({ok:false,error:e.message}));")
            assert result["ok"], result
            return result.get("value")

        state = call("state")
        assert state["state"]["settings"]["mode"] == "off"
        # Create and manage real Firefox tabs through extension APIs.
        tab = api(f"return await browser.tabs.create({{url:{json.dumps(origin + '/article')},active:false}});")
        wait.until(lambda d: api(f"return (await browser.tabs.get({tab['id']})).status;") == "complete")
        call("workspace-save", name="Test workspace")
        call("park", id=tab["id"])
        saved = call("state")["state"]["parked"]
        assert len(saved) == 1 and saved[0]["url"] == origin + "/article"
        parked_id = saved[0]["id"]
        assert "parked.html" in api(f"return (await browser.tabs.get({tab['id']})).url;")
        driver.refresh()
        wait.until(lambda d: "Reading fixture" in d.find_element(By.ID, "parked").text)
        assert call("state")["state"]["parked"][0]["id"] == parked_id
        call("restore", id=parked_id)
        driver.switch_to.window(dashboard)
        assert call("state")["state"]["parked"] == []
        wait.until(lambda d: api(f"return (await browser.tabs.get({tab['id']})).status;") == "complete")
        workspace = call("state")["state"]["workspaces"][0]
        before = len(Fixture.paths)
        call("workspace-restore", id=workspace["id"])
        assert len(call("state")["state"]["parked"]) == 1
        assert len(Fixture.paths) == before, "Restoring parked workspace contacted the original website"
        # Verify atomic reject: invalid backups must not overwrite saved data.
        response = driver.execute_async_script("const done=arguments[arguments.length-1]; browser.runtime.sendMessage({type:'import',data:{version:9}}).then(done);")
        assert not response["ok"]
        assert len(call("state")["state"]["parked"]) == 1
        # Test settings validation and streaming behavior in pure tests; real
        # optional permissions require a user gesture in browser chrome. Read
        # extraction can be exercised against the trusted localhost fixture
        # through WebDriver, independently of privileged extension injection.
        driver.switch_to.new_window("tab")
        driver.get(origin + "/article")
        extraction = (ROOT / "extension/extract.js").read_text().split("*/", 1)[1].strip()
        article = driver.execute_script("return " + extraction)
        assert "Aster workspaces" in article["text"]
        assert "PRIVATE-FORM-TEXT" not in article["text"] and "DO-NOT-EXTRACT" not in article["text"]
        driver.close()
        driver.switch_to.window(dashboard)
        # Data enters the actual UI as text, never markup.
        call("workspace-save", name="<img src=x onerror=alert(1)>")
        driver.refresh()
        wait.until(lambda d: "<img src=x onerror=alert(1)>" in d.find_element(By.ID, "workspaces").text)
        assert not driver.find_elements(By.CSS_SELECTOR, "#workspaces img")
        artifacts = ROOT / "artifacts"
        artifacts.mkdir(exist_ok=True)
        for name, width, height in [("desktop", 1280, 900), ("deck", 1280, 800), ("mobile-layout", 390, 844)]:
            driver.set_window_size(width, height)
            assert driver.execute_script("return document.documentElement.scrollWidth <= window.innerWidth"), name
            driver.save_screenshot(str(artifacts / f"aster-{name}.png"))
        print("PASS: real Firefox addon, workspace UI, persistence, park/resume, lazy restoration, invalid import, extraction, text escaping and three viewport layouts")
        print("NOT TESTED: Android runtime, Steam Deck hardware, permission prompts, audio voices, real blocking interception, subscription DRM")
    finally:
        driver.quit()
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == "__main__":
    main()
