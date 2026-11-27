const { loadProfile, createControlUrl } = require('../../control-url');

function webErrorMessage(detail) {
  const diagnostic = [detail.errMsg, detail.message, detail.errCode, detail.errorCode]
    .filter(value => typeof value === 'string' || typeof value === 'number')
    .join(' ')
    .replace(/https?:\/\/[^\s"'<>]+/gi, '[URL]')
    .replace(/[0-9a-f]{64}/gi, '[密钥]');

  if (typeof wx.getDeviceInfo === 'function' && wx.getDeviceInfo().platform === 'devtools') {
    console.warn('[遥控台 WebView]', diagnostic || '微信未提供具体错误');
  }

  if (/业务域名|domain.*(?:list|valid|whitelist|allow)|not.*allowed.*domain/i.test(diagnostic)) {
    return '微信未放行这个服务器的域名。请在小程序后台添加并验证对应的业务域名。';
  }
  if (/certificate|\bssl\b|\btls\b|证书/i.test(diagnostic)) {
    return '遥控台的 HTTPS 连接失败。请联系管理员检查站点证书。';
  }
  if (/timeout|net::|network|超时|网络/i.test(diagnostic)) {
    return '暂时无法连接遥控台。请检查网络后重试。';
  }
  return '请检查网络后重试。如果仍无法打开，请联系管理员确认小程序的业务域名配置。';
}

Page({
  data: { controlUrl: '', configured: false, error: '' },

  onShow() {
    const controlUrl = this.configuredControlUrl();
    if (controlUrl !== this.profileUrl) this.showControl(controlUrl);
  },

  openControl() {
    this.showControl(this.configuredControlUrl());
  },

  configuredControlUrl() {
    const profile = loadProfile();
    return profile ? createControlUrl(profile) : '';
  },

  showControl(controlUrl) {
    this.profileUrl = controlUrl;
    this.setData({
      controlUrl: controlUrl,
      configured: Boolean(controlUrl),
      error: '',
    });
  },

  onWebError({ detail = {} } = {}) {
    if (this.data.controlUrl) this.setData({ controlUrl: '', error: webErrorMessage(detail) });
  },

  openProfile() {
    wx.switchTab({ url: '/pages/profile/profile' });
  },
});
