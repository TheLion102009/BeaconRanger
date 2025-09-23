# AdjustableBeaconRange

AdjustableBeaconRange is a Minecraft plugin that allows you to customize the range of beacons. Perfect for servers running Paper or Folia on Minecraft 1.21.x, this plugin gives you full control over beacon effects.

## Features

- Adjust beacon range via the configuration file.
- Fully compatible with Minecraft 1.21.x.
- Works with Paper and Folia for optimal server performance.
- Simple setup, no complicated commands required.

## Installation

1. Download the latest `.jar` from the [releases page](#).
2. Place the `.jar` file in your server's `plugins` folder.
3. Start your server to generate the default configuration file.
4. Open `plugins/AdjustableBeaconRange/config.yml` and adjust the beacon range settings as desired.
5. Restart your server or use `/abr reload` (if implemented) to apply changes.

## Configuration

The configuration file allows you to set the beacon range globally or per beacon type. Example:

```yaml
# Beacon Ranger Konfiguration

# Reichweite der Beacons (zwischen 10 und 1000 Blöcken)
beacon-range: 100

# Automatisches Neuladen der Beacons in Sekunden (0 = deaktiviert)
update-interval: 300

# Lade Chunks um Beacons herum automatisch
load-beacon-chunks: true

# Debug-Modus (zeigt zusätzliche Informationen in der Konsole)
debug: false
