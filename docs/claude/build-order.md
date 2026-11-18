# Build order (historical)

> Part of the MiniPolya CLAUDE.md doc set. **Read this only for the original vertical-slice order the project was built in — it is history, not a to-do list.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Build Order

Implement in this order. Each step should be working and tested before moving to the next.

**For every step**: write the Liquibase changeset SQL file → add it to `db.changelog-master.yaml` → write the JPA entity → write the MapStruct mapper + DTOs → write the repository → write the service → write the controller. Each step produces a complete, working vertical slice.

1. **Project setup** — `db.changelog-master.yaml` (empty initially), `001-extensions.sql` (btree_gist), AppProperties, SecurityConfig skeleton, ApiResponse wrapper, GlobalExceptionHandler
2. **Auth + Users** — `002-create-users.sql` changeset → User entity → UserMapper → UserRepository → AuthService (Telegram initData verification) → JwtProvider → JwtAuthFilter → AuthController → UserService → UserController (profile, language)
3. **Stadiums** — `003-create-stadiums.sql` changeset (stadiums + stadium_photos + stadium_amenities) → Stadium/StadiumPhoto/StadiumAmenities entities → StadiumMapper → StadiumRepository → StadiumService → StadiumController (CRUD for owners, public listing/detail/search for players)
4. **Pricing** — `004-create-pricing-rules.sql` changeset → PricingRule entity → PricingRuleMapper → PricingService, PriceCalculator utility
5. **Booking Series** — `005-create-booking-series.sql` changeset → BookingSeries entity (used later by recurring bookings, but table needed for FK)
6. **Teams** — `006-create-teams.sql` changeset (teams + team_members) → Team/TeamMember entities → TeamMapper → TeamRepository → TeamService → TeamController
7. **Games** — `007-create-games.sql` changeset → Game entity → GameMapper → GameRepository (booking_id FK deferred until bookings table exists)
8. **Bookings** — `008-create-bookings.sql` changeset (bookings table + EXCLUDE constraint + games.booking_id FK + indexes) → Booking entity → BookingMapper → BookingRepository → BookingService → BookingController → BookingExpiryJob
9. **Payments** — `009-create-payments.sql` changeset → Payment entity → PaymentMapper → PaymentRepository → PaymentService → Payme/Click webhook integration
10. **Game Participants** — `010-create-game-participants.sql` changeset → GameParticipant entity → GameService (all 3 flows: casual, open, team_vs_team) → GameController → GameTimeoutJob
11. **Game Chat** — `011-create-game-messages.sql` changeset → GameMessage entity → simple message endpoints
12. **Reviews & Favorites** — `012-create-reviews-favorites.sql` changeset → Review/Favorite entities → ReviewService → ReviewController
13. **Stadium Closures & Promotions** — `013-create-stadium-closures-promotions.sql` changeset → StadiumClosure/Promotion entities → PromotionExpiryJob
14. **Availability** — calculate and return free slots (uses bookings, closures, pricing_rules, promotions, games)
15. **Notifications** — `014-create-notifications.sql` changeset → Notification entity → NotificationService → Telegram bot messages → ReminderJob
16. **Owner Applications** — `015-create-owner-applications.sql` changeset → OwnerApplication entity → admin review flow
17. **Audit Log** — `016-create-audit-log.sql` changeset → AuditLog entity → log important actions
18. **Triggers** — `017-create-triggers.sql` changeset → set_updated_at() trigger function for users, stadiums, teams, games, bookings
19. **Manual bookings** — owner adds walk-in bookings to their schedule (uses existing bookings table)
20. **Recurring bookings** — BookingSeries service + BookingSeriesJob (uses existing booking_series table from step 5)
21. **Owner dashboard APIs** — schedule view, stats, closures, promotions
22. **Admin APIs** — user management, stadium approval, platform stats

