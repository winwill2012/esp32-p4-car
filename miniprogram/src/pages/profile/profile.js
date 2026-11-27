const { DEFAULT_ORIGIN, PROFILE_KEY, loadProfile, parseProfile } = require('../../control-url');

Page({
  data: { server: DEFAULT_ORIGIN, access: '', configured: false, error: '', directOnly: false },

  onShow() {
    const profile = loadProfile();
    this.setData({
      server: profile ? profile.origin : DEFAULT_ORIGIN,
      access: '',
      configured: Boolean(profile),
      error: '',
      directOnly: Boolean(profile && profile.directOnly === true),
    });
  },

  onServerInput({ detail }) {
    this.setData({ server: detail.value, error: '' });
  },

  onAccessInput({ detail }) {
    this.setData({ access: detail.value, error: '' });
  },

  onDirectOnlyChange({ detail }) {
    this.setData({ directOnly: detail.value });
  },

  save() {
    const { server, access } = this.data;
    const profile = parseProfile(server, access, loadProfile());
    if (profile.error) {
      this.setData({ error: profile.error });
      return;
    }

    try {
      wx.setStorageSync(PROFILE_KEY, { origin: profile.origin, key: profile.key, directOnly: this.data.directOnly });
    } catch (error) {
      this.setData({ error: '无法保存配置，请检查小程序的存储空间。' });
      return;
    }
    this.setData({ server: profile.origin, access: '', configured: true, error: '' });
    wx.switchTab({ url: '/pages/drive/drive' });
  },
});
