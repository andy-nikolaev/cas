const puppeteer = require("puppeteer");

const cas = require("../../cas.js");

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);
    await cas.gotoLogin(page);

    await page.evaluate(() => typeof onRecaptchaV2Submit === "function");

    await cas.assertVisibility(page, "button.g-recaptcha");

    await browser.close();
})();
