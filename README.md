# FiestaQueueHold

Paper plugin source for Fiesta's separate waiting server. Version in plugin metadata: 1.1.1. Requires Paper 1.21 or newer and Java 21.

Install it on the waiting server only. It sets the loaded world's time, weather, PvP, world border, and several game rules. It prepares a spawn, gives a waiting-room kit, resets the waiting inventory after death, shows queue tips, and can forward waiting-room PvP events to the coordinator through a durable retry outbox.

When the optional stats bridge is configured, events are signed with HMAC and sent to the coordinator. The example config leaves the coordinator URL, node ID, and key blank. Generate a random 32-byte HMAC secret locally for each deployment. Keep it out of Git, along with live config, outbox, and player data.

The waiting server has separate world and player data from survival. Nothing in this plugin copies its inventory into the main game inventory. Fiesta's main server remains open to players using offline accounts through its separate authentication setup.

## Build

Set PLUGIN_API_CLASSPATH to Paper's API libraries and run ./build.sh. The result is written to build/plugin.jar.

Copy src/main/resources/config.yml.example to the plugin data folder as config.yml and fill in deployment-specific values privately. Leave the stats bridge blank if it is not being used.
