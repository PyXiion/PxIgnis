import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

export default defineConfig({
  site: "https://ignis.pyxiion.ru",
  integrations: [
    starlight({
      title: "PxIgnis",
      description:
        "Async Lua scripting engine for Minecraft Fabric — coroutines, mob AI, hot reload.",
      social: [
        { icon: "github", label: "GitHub", href: "https://github.com/pyxiion/PxIgnis" },
        { icon: "discord", label: "Discord", href: "https://discord.gg/FyPWDheyzs" },
        { icon: "download", label: "Modrinth", href: "https://modrinth.com/mod/pxignis" },
      ],
      sidebar: [
        {
          label: "Getting Started",
          slug: "guide/getting-started",
        },
        {
          label: "Guide",
          items: [
            { label: "1. Your first command", slug: "guide/01-your-first-command" },
            { label: "2. Events and storage", slug: "guide/02-events-and-storage" },
          ],
        },
        {
          label: "Core API",
          items: [
            { label: "mc.* API", slug: "reference/mc-api" },
            { label: "Commands API", slug: "reference/commands-api" },
            { label: "Async API", slug: "reference/async-api" },
            { label: "Nova (JIT)", slug: "reference/nova-api" },
            { label: "Events", slug: "reference/events" },
            { label: "Storage", slug: "reference/storage" },
            { label: "Language", slug: "reference/language" },
          ],
        },
        {
          label: "Types",
          items: [
            { label: "Player", slug: "reference/player-api" },
            { label: "World", slug: "reference/world-api" },
            { label: "Entity", slug: "reference/entity-api" },
            { label: "ItemStack", slug: "reference/itemstack-api" },
            { label: "Inventory", slug: "reference/inventory-api" },
            { label: "Container", slug: "reference/container-api" },
            { label: "Vector", slug: "reference/vector-api" },
            { label: "Structure", slug: "reference/structure-api" },
            { label: "Sidebar", slug: "reference/sidebar-api" },
            { label: "BossBar", slug: "reference/bossbar-api" },
            { label: "Hologram", slug: "reference/hologram-api" },
            { label: "Region", slug: "reference/region-api" },
            { label: "Mob AI", slug: "reference/mob-ai" },
          ],
        },
        {
          label: "Libraries",
          items: [
            { label: "Overview", slug: "libraries/overview" },
            { label: "Format", slug: "libraries/format" },
            { label: "Simple", slug: "libraries/simple" },
            { label: "Chest GUI", slug: "libraries/chestgui" },
          ],
        },
        {
          label: "Examples",
          items: [
            { label: "Basic Commands", slug: "examples/basic-commands" },
            { label: "Events", slug: "examples/events" },
            { label: "Persistence", slug: "examples/persistence" },
          ],
        },
        {
          label: "Changelog",
          slug: "changelog",
        },
      ],
      customCss: ["./src/styles/custom.css"],
      editLink: {
        baseUrl: "https://github.com/pyxiion/PxIgnis/edit/main/site/",
      },
      head: [
        {
          tag: "script",
          content: `(function(){
  var p = new URLSearchParams(window.location.search);
  var lang = p.get('lang');
  if (!lang || (lang !== 'ru' && lang !== 'yru' && lang !== 'gru')) return;
  p.delete('lang');
  var qs = p.toString();
  var cleanUrl = window.location.pathname + (qs ? '?' + qs : '') + window.location.hash;
  var fullUrl = window.location.origin + cleanUrl;
  var target = lang === 'gru'
    ? 'https://translate.google.com/translate?sl=auto&tl=ru&u=' + encodeURIComponent(fullUrl)
    : 'https://translate.yandex.ru/translate?url=' + encodeURIComponent(fullUrl) + '&lang=en-ru';
  window.location.replace(target);
})();`,
        },
        {
          tag: "script",
          content: `(function(){
  function build(){
    if (document.getElementById('pxrp-translate-fab')) return;
    var url = window.location.href;
    var ya = 'https://translate.yandex.ru/translate?url='+encodeURIComponent(url)+'&lang=en-ru';
    var go = 'https://translate.google.com/translate?sl=auto&tl=ru&u='+encodeURIComponent(url);
    var fab = document.createElement('div');
    fab.id = 'pxrp-translate-fab';
    fab.className = 'pxrp-translate-fab';
    fab.innerHTML =
      '<span class="pxrp-translate-fab__label">RU</span>'+
      '<a href="'+ya+'" target="_blank" rel="noopener noreferrer" title="\\u041f\\u0435\\u0440\\u0435\\u0432\\u043e\\u0434 (\\u042f\\u043d\\u0434\\u0435\\u043a\\u0441)">\\u042f</a>'+
      '<a href="'+go+'" target="_blank" rel="noopener noreferrer" title="\\u041f\\u0435\\u0440\\u0435\\u0432\\u043e\\u0434 (Google)">G</a>';
    document.body.appendChild(fab);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', build);
  } else {
    build();
  }
})();`,
        },
      ],
    }),
  ],
});
