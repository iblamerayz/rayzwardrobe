# RayzWardrobe

**A customizable wardrobe system for Minecraft servers.**

Save, organize, and equip your favorite armor sets through an intuitive inventory GUI. RayzWardrobe focuses on a clean player experience, reliable item handling, and straightforward server administration.

<p align="center">
  <strong>Paper · Spigot · Bukkit · Purpur</strong>
</p>

---

## Overview

RayzWardrobe is a Minecraft server plugin that allows players to store and manage multiple armor outfits without repeatedly assembling them from their inventory.

Players can access their saved outfits through a GUI, switch between sets, customize outfit names, and personalize their wardrobe experience using available themes.

Built with server usability in mind, RayzWardrobe aims to provide a practical wardrobe system without unnecessary complexity.

## Features

- **Wardrobe GUI** — Manage and equip outfits through an inventory-based interface.
- **Unlimited Outfit Storage** — Save multiple armor sets without a fixed outfit limit.
- **Outfit Management** — Save, equip, and rename your outfits.
- **Multiple Themes** — Customize the wardrobe interface with available visual themes.
- **GUI Pagination** — Navigate wardrobe pages with permission-controlled access.
- **Equip Feedback** — Particle and sound effects when equipping outfits.
- **Item Safety** — Safeguards designed to prevent unintended item duplication.
- **Cooldown Support** — Configure or use available cooldown mechanisms to control repeated actions.
- **Configuration Support** — Customize supported settings to suit your server.
- **Localization** — Customize supported messages through `messages.yml`.
- **PlaceholderAPI Support** — Use supported placeholders where available.
- **Persistent Storage** — Save wardrobe data using the configured storage backend.

> Feature availability and individual settings depend on the released plugin version and configuration.

## Compatibility

| Component | Compatibility |
|---|---|
| Minecraft | See the release requirements |
| Server software | Paper, Spigot, Bukkit-compatible forks, Purpur |
| Java | See the selected release's requirements |
| PlaceholderAPI | Optional, where supported |

**Minecraft 26.3 compatibility:** Use a release explicitly built and tested for your target server version. Check the release notes before updating a production server.

## Installation

1. Download the RayzWardrobe JAR from the official release page.
2. Stop your Minecraft server.
3. Place the JAR inside your server's `plugins/` directory.
4. Start the server.
5. Review the generated RayzWardrobe configuration files.
6. Configure permissions and optional integrations as needed.
7. Test the wardrobe and equip behavior before deploying to your production server.

**Updating:** Back up your existing configuration and player data before replacing the plugin JAR. Avoid deleting the plugin's data folder when upgrading.

## Commands

| Command | Description |
|---|---|
| `/wardrobe` | Open the wardrobe interface |
| `/rwreload` | Reload RayzWardrobe configuration |
| `/rwcredits` | Display plugin credits |

Check the installed version's command registration and release notes if any command differs.

## Permissions

| Permission | Description |
|---|---|
| `rayzwardrobe.use.page2` | Access wardrobe page 2 |
| `rayzwardrobe.use.page3` | Access wardrobe page 3 |
| `rayzwardrobe.use.page4` | Access wardrobe page 4 |
| `rayzwardrobe.use.page5` | Access wardrobe page 5 |
| `rayzwardrobe.use.page6` | Access wardrobe page 6 |
| `rayzwardrobe.use.page7` | Access wardrobe page 7 |
| `rayzwardrobe.use.page8` | Access wardrobe page 8 |
| `rayzwardrobe.reload` | Access the reload command |
| `rayzwardrobe.admin` | Administrative access to wardrobe permissions |
| `rayzwardrobe.slot.1` | Access wardrobe slot 1 |
| `rayzwardrobe.slot.2` | Access wardrobe slot 2 |
| `rayzwardrobe.slot.3` | Access wardrobe slot 3 |
| `rayzwardrobe.slot.4` | Access wardrobe slot 4 |
| `rayzwardrobe.slot.5` | Access wardrobe slot 5 |
| `rayzwardrobe.slot.6` | Access wardrobe slot 6 |
| `rayzwardrobe.slot.7` | Access wardrobe slot 7 |

Permission defaults may vary by release and server configuration. Review the generated `plugin.yml` and your permissions plugin before assigning access.

## Themes

RayzWardrobe supports multiple interface themes, including:

- Classic
- Darkmode
- Galaxy
- Neon
- Cyber
- Royal
- Pastel
- Sunset

Available themes and their permission requirements depend on the installed version and configuration.

## Configuration

RayzWardrobe generates its configuration files when first started.

Depending on the release, these may include:

- `config.yml` — General plugin settings.
- `messages.yml` — Player-facing messages and language customization.
- Player data files or database settings — Wardrobe persistence.

### Customizing messages

Edit the supported messages in `messages.yml` to match your server's language and style.

### Configuration best practices

- Back up configuration files before editing them.
- Preserve existing keys when updating.
- Check the console for YAML formatting errors.
- Use `/rwreload` where supported to reload settings.
- Restart the server if a change requires full plugin reinitialization.

Always use the configuration generated by your installed release as the source of truth.

## PlaceholderAPI

RayzWardrobe supports PlaceholderAPI where included in the selected release.

Install PlaceholderAPI separately if required, then consult the release documentation for the exact placeholders available. Do not assume an undocumented placeholder exists.

## Data Storage and Safety

RayzWardrobe stores wardrobe data using its configured persistence backend.

For safe server maintenance:

- Back up the plugin's data directory before updating.
- Back up your database if using MySQL.
- Avoid manually editing serialized item data.
- Do not delete player data to troubleshoot unrelated GUI problems.
- Test updates on a staging server when possible.

Inventory and persistence safeguards reduce risk, but regular backups remain essential.

## Troubleshooting

### The wardrobe command does not work

- Confirm RayzWardrobe loaded successfully.
- Check the console for startup errors.
- Verify the command spelling and your permissions.

### Players cannot access certain pages or slots

- Check the relevant `rayzwardrobe.use.pageN` or `rayzwardrobe.slot.N` permission.
- Review inherited permissions and group configuration.
- Verify the default permissions for your installed version.

### Outfits do not persist

- Check for file permission or database connection errors.
- Review the console for serialization or save failures.
- Verify that the server can write to the plugin's data directory.
- Restore from a backup if data corruption is suspected.

### Problems after updating

1. Back up the current plugin data.
2. Check the release notes and supported server versions.
3. Review startup logs for API or dependency errors.
4. Test with a clean test server before making destructive changes to production data.

### GUI or item-related issues

Record the steps required to reproduce the problem, the server version, the plugin version, and any relevant console errors.

## Development

RayzWardrobe is a Java-based Minecraft plugin.

To contribute:

1. Fork the repository.
2. Create a branch for your changes.
3. Keep changes focused and compatible with existing behavior.
4. Build the project using its configured build system.
5. Test changes on the intended Minecraft/Paper version.
6. Submit a pull request describing the changes and verification performed.

### Build with Gradle

```bash
./gradlew clean build
```

The generated JAR is typically located in `build/libs/`. Use the project's actual Gradle configuration to identify the intended distributable artifact.

## Bug Reports

When reporting a bug, include:

- RayzWardrobe version
- Minecraft and server software version
- Java version
- Relevant configuration
- Steps to reproduce
- Expected behavior
- Actual behavior
- Relevant console logs

For suspected item duplication or data loss, include the exact interaction sequence and avoid reproducing the issue on a production server.

## Support

For support, updates, and release information, use the official RayzWardrobe resource page:

- [Modrinth](https://modrinth.com/plugin/rayzwardrobe)
- [GitHub](https://github.com/)

Replace the generic GitHub link with the actual source repository if it is public.

## License

Refer to the repository's `LICENSE` file for the applicable license and redistribution terms.

If no license file exists, the project should not be assumed to be open-source or freely redistributable.

---

<p align="center">
  <strong>RayzWardrobe</strong><br>
  A straightforward wardrobe system for Minecraft servers.
</p>
