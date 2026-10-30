# CoinShop (v1.0.0)

A complete, persistent Coin economy and multi-category GUI shop plugin for **Paper 1.21.x and 26.x** servers running **Java 21 or Java 25**.

---

## 1. Supported Platform & Compatibility

| Component | Supported Version | Notes |
| :--- | :--- | :--- |
| **Server Software** | **Paper / Purpur `1.21.x` & `26.x`** | Uses official Paper Adventure Component & `ItemStack#serializeAsBytes` APIs (`api-version: '1.21'`). |
| **Java Runtime** | **Java 21 LTS & Java 25** | Compiled with `--release 21` for full compatibility across Java 21 through Java 25. |
| **PlaceholderAPI** | `2.11.6+` *(Optional)* | Soft dependency. Plugin starts and operates normally without it. |
| **EssentialsX** | `2.20+` *(Optional)* | CoinShop maintains its own dedicated `Coin` currency and never overwrites or merges with EssentialsX money. |

---

## 2. Building the Project & Exact JAR Location

### Prerequisites
* JDK 21 or JDK 25 installed (`java -version`)
* Apache Maven 3.9+ installed (`mvn -version`)

### Build & Run Automated Tests
```bash
mvn clean test package
```

**Output JAR:** `target/CoinShop-1.0.0.jar`

3. Installation

1.  Copy target/CoinShop-1.0.0.jar into your Paper server's plugins/ directory.
2.  Restart the server.
3.  Default configuration files (config.yml, messages.yml,
    categories/spawners.yml, categories/chat-color.yml, shops/spawners.yml,
    shops/chat-color.yml, and database.db) will be generated inside
    plugins/CoinShop/.

4. Commands & Permissions

Root Command: /coinshop (Aliases: /cshop, /coins, /cs)

| Command                                         | Permission                                | Description                                                                                                                  |
| :---------------------------------------------- | :---------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------- |
| `/coinshop`                                     | `coinshop.use` *(default: true)*          | Opens the main CoinShop category GUI.                                                                                        |
| `/coinshop shop <category>`                     | `coinshop.use` *(default: true)*          | Opens a specific category shop GUI directly.                                                                                 |
| `/coinshop balance`                             | `coinshop.balance` *(default: true)*      | Displays your exact and formatted Coin balance.                                                                              |
| `/coinshop balance <player>`                    | `coinshop.balance.others` *(default: op)* | Displays another player's Coin balance.                                                                                      |
| `/coinshop top`                                 | `coinshop.top` *(default: true)*          | Displays the top 10 Coin balances.                                                                                           |
| `/coinshop history [player]`                    | `coinshop.history` *(default: op)*        | Views recent Coin transactions for a player.                                                                                 |
| `/coinshop give <player> <amount>`              | `coinshop.admin.give` *(default: op)*     | Deposits Coins into a player's balance.                                                                                      |
| `/coinshop take <player> <amount>`              | `coinshop.admin.take` *(default: op)*     | Withdraws Coins from a player's balance.                                                                                     |
| `/coinshop set <player> <amount>`               | `coinshop.admin.set` *(default: op)*      | Sets a player's exact Coin balance.                                                                                          |
| `/coinshop addcategory <id>`                    | `coinshop.admin.category` *(default: op)* | Creates `categories/<id>.yml` and `shops/<id>.yml`.                                                                          |
| `/coinshop removecategory <id>`                 | `coinshop.admin.category` *(default: op)* | Deletes a category.                                                                                                          |
| `/coinshop addhanditem <category> [price] [id]` | `coinshop.admin.item` *(default: op)*     | Serializes the held item into `shops/<category>.yml`. If price is omitted, sets price to `UNSET` (unavailable until priced). |
| `/coinshop edititem <category> <id>`            | `coinshop.admin.item` *(default: op)*     | Opens the in-game property editor for an item.                                                                               |
| `/coinshop removeitem <category> <id>`          | `coinshop.admin.item` *(default: op)*     | Removes an item from a category's shop file.                                                                                 |
| `/coinshop editor [category]`                   | `coinshop.editor` *(default: op)*         | Opens the in-game GUI editor for categories or a specific shop.                                                              |
| `/coinshop validate`                            | `coinshop.validate` *(default: op)*       | Checks all YAML files for invalid materials, slot conflicts, or missing references.                                          |
| `/coinshop reload`                              | `coinshop.reload` *(default: op)*         | Validates all files and reloads configuration safely (preserves last known-good state on error).                             |
| `/coinshop info`                                | `coinshop.validate` *(default: op)*       | Shows plugin version, runtime, storage type, and loaded item counts.                                                         |

5. PlaceholderAPI Placeholders

When PlaceholderAPI is installed, the following placeholders are automatically
registered:

  - %coinshop_balance% — Exact numeric balance without commas (e.g., 1500000)
  - %coinshop_balance_raw% — Exact numeric balance with thousand separators
    (e.g., 1,500,000)
  - %coinshop_balance_formatted% — Short K/M/B/T display balance using floor
    truncation (e.g., 1.5M)
  - %coinshop_symbol% — Configured currency symbol (e.g., ⛃)
  - %coinshop_currency_name% — Singular or plural currency name (Coin / Coins)

6. Approved Reward Command Placeholders

When an item uses reward-type: COMMAND or reward-type: BOTH, commands run from
the server console with only the following sanitized placeholders substituted:

  - %player% — Buyer's username (sanitized to alphanumeric, _, and .)
  - %uuid% — Buyer's UUID
  - %amount% — Purchased stack amount
  - %price% — Exact Coin price paid
  - %item_id% — Shop item ID
  - %category_id% — Category ID

7. Backup, Migration & Limitations Notes

  - Single-Server Consistency: SQLite (database.db) with WAL mode provides
    atomic, thread-safe persistence for a single Paper server. Multi-server
    cross-proxy synchronization (e.g., Redis/MySQL cluster locking) is not
    enabled in v1.0.0.
  - Small-Caps Styling: Unicode small-caps (ᴄᴏɪɴ ꜱʜᴏᴘ) can be toggled via
    style.use-small-caps: true/false in config.yml. It uses standard Unicode
    glyphs supported by vanilla clients without requiring a custom resource
    pack.
  - Backups: Before editing YAML files manually or upgrading, back up the
    plugins/CoinShop/ folder (database.db, categories/, and shops/). All in-game
    editor saves write to a .tmp file first and perform an atomic move to
    prevent half-written files.
