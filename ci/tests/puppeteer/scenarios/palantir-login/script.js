const puppeteer = require("puppeteer");
const assert = require("assert");
const cas = require("../../cas.js");

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);
    await cas.goto(page, "https://localhost:8443/cas/palantir/dashboard");
    let response = await cas.loginWith(page, "casadmin", "password");
    await page.waitForTimeout(1000);
    await cas.log(`${response.status()} ${response.statusText()}`);
    await page.waitForTimeout(1000);
    await cas.screenshot(page);
    assert(response.status() === 200);
    response = await cas.goto(page, "https://localhost:8443/cas/palantir/dashboard/services");
    await cas.log(`${response.status()} ${response.statusText()}`);
    await cas.screenshot(page);
    assert(response.ok());
    await browser.close();
})();
