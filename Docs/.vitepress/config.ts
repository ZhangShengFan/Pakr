import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Pakr',
  description: '网页一键打包 APK',
  lang: 'zh-CN',
  base: '/',

  head: [
    ['link', { rel: 'icon', href: '/logo.jpg' }]
  ],

  themeConfig: {
    logo: '/logo.jpg',
    siteTitle: 'Pakr',

    nav: [
      { text: '指南', link: '/guide/introduction' },
      { text: '参考', link: '/reference/features' },
      {
        text: 'v1.0.0',
        items: [
          { text: '更新日志', link: '/changelog' }
        ]
      }
    ],

    sidebar: [
      {
        text: '开始',
        items: [
          { text: '介绍', link: '/guide/introduction' },
          { text: '快速开始', link: '/guide/quickstart' },
          { text: '部署流程', link: '/guide/deploy' }
        ]
      },
      {
        text: '参考',
        items: [
          { text: '功能特性', link: '/reference/features' },
          { text: '常见问题', link: '/reference/faq' }
        ]
      }
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/ZhangShengFan/Pakr' }
    ],

    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © 2024 ZhangShengFan'
    },

    search: {
      provider: 'local'
    },

    editLink: {
      pattern: 'https://github.com/ZhangShengFan/Pakr/edit/main/Docs/:path',
      text: '在 GitHub 上编辑此页'
    },

    lastUpdated: {
      text: '最后更新于'
    }
  }
})
