import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "Xime 五笔输入法",
  description: "基于 Rime 引擎构建的 Android 五笔输入法",
  lang: 'zh-CN',
  vite: {
    server: {
      host: '127.0.0.1',
      port: 3000
    }
  },
  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '使用文档', link: '/usage' },
      { text: '插件', link: '/plugins/' }
    ],

    sidebar: {
      '/': [
        {
          text: '开始',
          items: [
            { text: '简介', link: '/' },
            { text: '使用文档', link: '/usage' }
          ]
        },
        {
          text: '功能',
          items: [
            { text: '智能联想', link: '/features/smart-prediction' },
            { text: '语音转文本', link: '/features/speech-to-text' },
            { text: '剪贴板', link: '/features/clipboard' },
            { text: '快捷发送', link: '/features/quick-send' },
            { text: '键盘调节', link: '/features/keyboard-adjustment' },
            { text: '表情', link: '/features/emoji' },
            { text: '部署方案', link: '/features/deployment' },
            { text: '输入方案', link: '/features/input-scheme' }
          ]
        },
        {
          text: '插件',
          items: [
            { text: '插件列表', link: '/plugins/' },
            { text: '开发指南', link: '/plugins/PLUGIN_DEVELOPMENT_GUIDE' },
            { text: '测试指南', link: '/plugins/TESTING' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/ximeiorg/Xime' }
    ],

    footer: {
      message: '基于 GPLv3 许可发布',
      copyright: 'Copyright © 2024 Xime'
    },

    search: {
      provider: 'local'
    },

    outline: {
      label: '目录'
    },

    docFooter: {
      prev: '上一页',
      next: '下一页'
    },

    lastUpdated: {
      text: '最后更新',
      formatOptions: {
        dateStyle: 'short',
        timeStyle: 'short'
      }
    }
  }
})
