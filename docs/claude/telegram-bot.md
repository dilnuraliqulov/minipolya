# Telegram bot

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching the bot: webhook/polling, the conversation, bot texts or the banner.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Telegram bot

What a user sees in the chat before and after Start, and the buttons that open the Mini
App. Code in `telegram/`; messages go out through `service/TelegramBotService` (also used
by `NotificationService`), which calls the Bot API through `telegram/TelegramApi`.

## Receiving updates

- **webhook** (production, `TELEGRAM_BOT_MODE=webhook`, the default): Telegram calls
  `POST /webhooks/telegram` (public, no JWT). `X-Telegram-Bot-Api-Secret-Token` must equal
  `TELEGRAM_WEBHOOK_SECRET` (constant-time); otherwise **403**, and with no secret set every
  call is 403. Register it with `setWebhook … secret_token=` (see "After the first deploy").
- **polling** (`TELEGRAM_BOT_MODE=polling`, **`dev` profile only**): `TelegramPolling` calls
  `deleteWebhook`, then loops on `getUpdates`. `TelegramBotModeGuard` refuses to start with
  polling outside `dev` — so never in prod — or with any other mode value. Use a separate
  test bot: polling deletes that bot's webhook.
- Both hand each update to **`TelegramUpdateHandler`**, the one handler. It first claims the
  `update_id` in `telegram_processed_updates` (`INSERT … ON CONFLICT DO NOTHING`, its own
  transaction): a redelivered update never runs twice. A failure while handling is logged
  and not retried (the webhook still answers 200, or Telegram resends forever).
  `TelegramUpdateCleanupJob` forgets ids older than a week. Only private chats are answered.
- **Logging**: only `update_id`, the update type and the Telegram user id — never message
  text, a contact or a phone number; a phone that must be mentioned is masked
  (`PhoneMask`: `+998 90 *** ** 58`). Of an unexpected exception only the class is logged.
  The bot token is part of every API URL, so `TelegramApi` never lets a URL reach a log.
- `app.bot.enabled=false` (or no `BOT_TOKEN`) switches every Bot API call off. `@DatabaseTest`
  sets it, so tests never message real chats with the token from `.env`.

## Bot profile, set on startup

`TelegramBotSetup` runs in the background after startup (`app.bot.setup-on-startup`,
default true): `setMyDescription` (the text before Start), `setMyShortDescription` and
`setMyCommands` (`/start`, `/help`, `/language`) for **uz with no `language_code`** (the
default for everyone) and for `ru` and `en`, plus the default `setChatMenuButton`
(`web_app`, text "MiniPolya", `MINIAPP_URL`). Each value is **read first** (`getMy…`,
`getChatMenuButton`) and set only when it differs, so a restart changes nothing at
Telegram. A failure is logged and never stops the application.

## The conversation (`TelegramBotFlow`)

Every user is found or created by **`AuthService.findOrCreateFromTelegram`** — the one
place a Telegram user is created, shared with the Mini App sign-in (a new user: name from
Telegram, language uz). Every message is in the user's saved `user.language`.

- **Blocked** user: only "Hisobingiz bloklangan." plus the support contact, for any message
  or button.
- **`/start`**, no phone yet: two messages. The banner photo with the greeting and an inline
  `web_app` button "⚽ MiniPolya'ni ochish" (with the deep link, if any); then the request for
  the phone with a one-button reply keyboard, "📱 Telefon raqamni yuborish" (`request_contact`,
  resized, one-time, placeholder). With a phone: only the banner, with "Xush kelibsiz" and the
  open button. The first name is HTML-escaped.
- **Deep links**: `/start <payload>` (e.g. `game_<id>` from an invite,
  `t.me/{bot}?start=game_{id}`) — if it is a valid start parameter (`[A-Za-z0-9_-]{1,64}`),
  the open button goes to `MINIAPP_URL?startapp=<payload>`. A `web_app` button gives the Mini
  App no `start_param`, so **the Mini App reads `startapp` from its own URL**. When the phone
  is still to come, the payload is also kept in memory for an hour (`StartPayloads`) and
  carried by the button sent after the phone.
- **The contact**: accepted only if `contact.user_id == from.id` (otherwise "Iltimos,
  oʻzingizning raqamingizni yuboring…" with the keyboard again); normalised to
  `+998XXXXXXXXX`, any other country refused; a phone another account has is **never
  overwritten or merged** ("Bu raqam boshqa hisobga biriktirilgan." + support, a masked
  warning in the log). Saved by `UserService.savePhoneFromBot` (the profile's uniqueness rule,
  audit `PHONE_SHARED_VIA_BOT`), then two messages: the masked number with the keyboard
  removed, and "Endi maydonni tanlang va band qiling 👇" with the open button.
- Any other text: before the phone, the request again with the button; after it, the open button.
- **`/help`**: the three steps, the cancellation rule in one line, the support contact
  (when `SUPPORT_USERNAME` is set) and the open button.
- **`/language`**: inline buttons "🇺🇿 Oʻzbekcha", "🇷🇺 Русский", "🇬🇧 English"
  (`lang:uz|ru|en`). The choice is saved through `UserService.updateProfile` — the same
  `user.language` the Mini App uses, audited `PROFILE_UPDATED` — and the message is edited
  to confirm it.
- With `MINIAPP_URL` missing or not https (Telegram refuses such a `web_app` button) the open
  buttons are left out and the texts still go out; `TelegramBotModeGuard` logs a warning at
  startup (under `prod` it refuses to start).

## Texts and the banner

- **All bot texts** live in `src/main/resources/telegram/bot_uz.properties` (the default and
  the fallback), `bot_ru` and `bot_en`, read as UTF-8 by `BotTexts`. `{name}` placeholders
  are replaced literally (no MessageFormat, so apostrophes stay). `BotTextsTest` checks the
  three have the same keys and fit Telegram's limits. Don't put a bot text in Java.
- The banner is `src/main/resources/telegram/banner.png` (1280×640; the current one is a
  placeholder). It is uploaded on first use; Telegram's file_id is kept in
  `telegram_bot_values` (`banner_file_id`) tied to the image's hash, so replacing the PNG
  uploads the new one once. If Telegram refuses the photo, the caption goes out as text.

