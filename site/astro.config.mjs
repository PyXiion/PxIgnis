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
    }),
  ],
});
