const DEFAULT_ORIGIN = 'https://iot.welinklab.com';
const CONTROL_PATH = '/p4-intercom/rtc/';
const PROFILE_KEY = 'p4-car-server-v1';
const VIEWER_KEY = /^[0-9a-f]{64}$/;
const PUBLIC_HOST = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$/;

function serverOrigin(input) {
  const host = input.trim().toLowerCase().replace(/^https:\/\//, '').replace(/\/$/, '');
  return host.length <= 253 && PUBLIC_HOST.test(host) ? 'https://' + host : '';
}

function parseProfile(server, access, previous) {
  const input = access.trim();
  if (input.includes('#') || input.includes('://')) {
    const link = /^https:\/\/([^/?#]+)\/p4-intercom(?:\/rtc)?\/?#([0-9a-f]{64})(?:&theme=light|&ice=direct)*$/i.exec(input);
    if (!link) return { error: '请粘贴完整的 HTTPS 遥控链接，包含 # 后的访问密钥。' };
    const origin = serverOrigin(link[1]);
    if (!origin) return { error: '遥控链接需要使用公网 HTTPS 域名。' };
    return { origin, key: link[2].toLowerCase() };
  }

  const origin = serverOrigin(server);
  if (!origin) return { error: '请输入有效的服务器 HTTPS 域名。' };
  const savedKey = previous && previous.origin === origin ? previous.key : '';
  const key = input.toLowerCase() || savedKey;
  if (!VIEWER_KEY.test(key)) return { error: '请粘贴完整的遥控链接，或填写服务器的 64 位访问密钥。' };
  return { origin, key };
}

function loadProfile() {
  const profile = wx.getStorageSync(PROFILE_KEY);
  if (!profile || typeof profile.origin !== 'string' || serverOrigin(profile.origin) !== profile.origin ||
      typeof profile.key !== 'string' || !VIEWER_KEY.test(profile.key)) return null;
  return profile;
}

function createControlUrl(profile) {
  return profile.origin + CONTROL_PATH + '#' + profile.key + '&theme=light' + (profile.directOnly === true ? '&ice=direct' : '');
}

module.exports = { DEFAULT_ORIGIN, PROFILE_KEY, loadProfile, parseProfile, createControlUrl };
