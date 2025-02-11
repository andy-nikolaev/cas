const puppeteer = require("puppeteer");
const cas = require("../../cas.js");
const assert = require("assert");

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);

    await cas.gotoLogin(page, "https://apereo.github.io");
    await cas.log("Checking for page URL redirecting, based on service policy...");
    await cas.logPage(page);
    await page.waitForTimeout(1000);
    let url = await page.url();
    assert(url.startsWith("https://localhost:8444/cas/login"));

    await cas.gotoLogin(page, "https://github.com/apereo/cas");
    await cas.log("Checking for page URL...");
    url = await page.url();
    await cas.log(url);
    assert(url.startsWith("https://localhost:8444/cas/login"));
    await browser.close();
})();
