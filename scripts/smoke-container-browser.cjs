const fs = require('node:fs/promises');
const path = require('node:path');
const { createRequire } = require('node:module');
const loadFrontend = createRequire(path.resolve('frontend/package.json'));
const { chromium } = loadFrontend('@playwright/test');

async function main() {
  const origin = 'https://localhost:18443';
  const account = JSON.parse(await fs.readFile('.artifacts/R-e2e-accounts.json', 'utf8')).buyer;
  const browser = await chromium.launch();
  // 仅本机固定地址使用项目生成的自签名TLS；不向其他域开放此选项。
  const context = await browser.newContext({ baseURL: origin, ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
  try {
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByLabel('用户名', { exact: true }).fill(account.username);
    await page.locator('input[autocomplete="current-password"]').fill(account.password);
    const login = page.waitForResponse(r => r.url().endsWith('/api/v1/auth/login') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    const response = await login;
    if (response.status() !== 200) throw new Error(`Container login HTTP ${response.status()}`);
    await page.waitForURL(url => !url.pathname.endsWith('/login'));
    const refresh = page.waitForResponse(r => r.url().endsWith('/api/v1/auth/refresh'));
    await page.reload();
    if ((await refresh).status() !== 200) throw new Error('Cookie and CSRF refresh failed');
    await page.goto('/account/orders');
    await page.getByRole('heading', { name: '我的订单', exact: true }).waitFor();
    await page.locator('details.account-menu > summary').click();
    const logout = page.waitForResponse(r => r.url().endsWith('/api/v1/auth/logout'));
    await page.getByRole('button', { name: /退出/ }).click();
    if ((await logout).status() !== 200) throw new Error('Cookie and CSRF logout failed');
    const record = { status:'PASSED', origin, login:200, refresh:200, logout:200, memberOrdersPage:true,
      csrfEnabled:true, scope:'Real Chromium through non-root Nginx and HTTPS; local self-signed certificate only', checkedAt:new Date().toISOString() };
    await fs.writeFile('.artifacts/R-container-browser.json', JSON.stringify(record, null, 2)+'\n');
    console.log(JSON.stringify(record));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
