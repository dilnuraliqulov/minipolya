package uz.minipolya.minipolya_backend.provider.fake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The fake's state in the {@code fake_payment_*} tables (changeset 044), so it survives a
 * restart. Plain JDBC rather than JPA entities: nothing of the fake is mapped by Hibernate,
 * so production's schema validation and entity model never see it.
 *
 * <p>Every provider call is one {@code REQUIRES_NEW} transaction, committed on its own:
 * our own transaction rolling back afterwards does not undo what "the provider" did, just
 * as it would not at ATMOS. Cards and operations are read {@code FOR UPDATE}, so two
 * parallel calls on the same card or payment run one after the other.
 */
public class JdbcFakeProviderStore implements FakeProviderStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcFakeProviderStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    // ---- Bindings ----

    @Override
    public void saveBinding(FakeBinding b) {
        jdbc.update("""
                        INSERT INTO fake_payment_bindings (binding_id, masked_pan, last4, expiry, masked_phone,
                            card_holder, code_sent_at, confirmed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (binding_id) DO UPDATE
                        SET code_sent_at = EXCLUDED.code_sent_at, confirmed_at = EXCLUDED.confirmed_at
                        """,
                b.getBindingId(), b.getMaskedPan(), b.getLast4(), b.getExpiry(), b.getMaskedPhone(),
                b.getCardHolder(), b.getCodeSentAt(), b.getConfirmedAt(), b.getCreatedAt());
    }

    @Override
    public Optional<FakeBinding> binding(String bindingId) {
        return jdbc.query("SELECT * FROM fake_payment_bindings WHERE binding_id = ? FOR UPDATE",
                BINDING, bindingId).stream().findFirst();
    }

    // ---- Cards ----

    @Override
    public void saveCard(FakeCard c) {
        jdbc.update("""
                        INSERT INTO fake_payment_cards (card_id, token_hash, masked_pan, last4, expiry, card_holder,
                            masked_phone, balance, is_active, created_at, removed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (card_id) DO UPDATE
                        SET balance = EXCLUDED.balance, is_active = EXCLUDED.is_active, removed_at = EXCLUDED.removed_at
                        """,
                c.getCardId(), c.getTokenHash(), c.getMaskedPan(), c.getLast4(), c.getExpiry(), c.getCardHolder(),
                c.getMaskedPhone(), c.getBalance(), c.isActive(), c.getCreatedAt(), c.getRemovedAt());
    }

    @Override
    public Optional<FakeCard> cardByTokenHash(String tokenHash) {
        return jdbc.query("SELECT * FROM fake_payment_cards WHERE token_hash = ? FOR UPDATE",
                CARD, tokenHash).stream().findFirst();
    }

    @Override
    public Optional<FakeCard> card(String cardId) {
        return jdbc.query("SELECT * FROM fake_payment_cards WHERE card_id = ? FOR UPDATE",
                CARD, cardId).stream().findFirst();
    }

    // ---- Charges and holds ----

    @Override
    public void saveOperation(FakeOperation o) {
        jdbc.update("""
                        INSERT INTO fake_payment_operations (id, kind, account, card_id, last4, otp_required, amount,
                            commission, status, error_code, hold_till, succeeded_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE
                        SET status = EXCLUDED.status, error_code = EXCLUDED.error_code,
                            succeeded_at = EXCLUDED.succeeded_at, updated_at = EXCLUDED.updated_at
                        """,
                o.getId(), o.getKind().name(), o.getAccount(), o.getCardId(), o.getLast4(), o.isOtpRequired(),
                o.getAmount(), o.getCommission(), o.getStatus().name(), o.getErrorCode(), o.getHoldTill(),
                o.getSucceededAt(), o.getCreatedAt(), o.getUpdatedAt());
        for (FakeSplit s : o.getSplits()) {
            jdbc.update("""
                            INSERT INTO fake_payment_splits (transaction_id, operation_id, split_index, recipient_id,
                                is_platform, amount, commission, success_trans_id, reversed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (transaction_id) DO UPDATE
                            SET success_trans_id = EXCLUDED.success_trans_id, reversed_at = EXCLUDED.reversed_at
                            """,
                    s.getTransactionId(), o.getId(), s.getPosition(), s.getRecipientId(), s.isPlatform(),
                    s.getAmount(), s.getCommission(), s.getSuccessTransId(), s.getReversedAt());
        }
    }

    @Override
    public Optional<FakeOperation> operation(String id) {
        return withSplits(jdbc.query("SELECT * FROM fake_payment_operations WHERE id = ? FOR UPDATE",
                OPERATION, id));
    }

    @Override
    public Optional<FakeOperation> operationByAccount(FakeOperation.Kind kind, String account) {
        return withSplits(jdbc.query("SELECT * FROM fake_payment_operations WHERE kind = ? AND account = ? FOR UPDATE",
                OPERATION, kind.name(), account));
    }

    @Override
    public Optional<FakeOperation> operationByTransaction(String transactionId) {
        return withSplits(jdbc.query("""
                        SELECT o.* FROM fake_payment_operations o
                        WHERE o.id = (SELECT operation_id FROM fake_payment_splits WHERE transaction_id = ?)
                        FOR UPDATE
                        """, OPERATION, transactionId));
    }

    @Override
    public Optional<FakeOperation> operationBySuccessId(String successTransId) {
        return withSplits(jdbc.query("""
                        SELECT o.* FROM fake_payment_operations o
                        WHERE o.id = (SELECT operation_id FROM fake_payment_splits WHERE success_trans_id = ?)
                        FOR UPDATE
                        """, OPERATION, successTransId));
    }

    @Override
    public void saveReverse(FakeReverse r) {
        jdbc.update("""
                        INSERT INTO fake_payment_reverses (id, operation_id, transaction_id, amount, outcome,
                            error_code, reason, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                r.getId(), r.getOperationId(), r.getTransactionId(), r.getAmount(), r.getOutcome().name(),
                r.getErrorCode(), r.getReason(), r.getCreatedAt());
    }

    // ---- The dev view ----

    @Override
    public List<FakeCard> latestCards(int limit) {
        return jdbc.query("SELECT * FROM fake_payment_cards ORDER BY created_at DESC LIMIT ?", CARD, limit);
    }

    @Override
    public List<FakeOperation> latestOperations(FakeOperation.Kind kind, int limit) {
        List<FakeOperation> operations = jdbc.query(
                "SELECT * FROM fake_payment_operations WHERE kind = ? ORDER BY created_at DESC LIMIT ?",
                OPERATION, kind.name(), limit);
        operations.forEach(this::loadSplits);
        return operations;
    }

    @Override
    public List<FakeReverse> latestReverses(int limit) {
        return jdbc.query("SELECT * FROM fake_payment_reverses ORDER BY created_at DESC LIMIT ?", REVERSE, limit);
    }

    // ---- Rows ----

    private Optional<FakeOperation> withSplits(List<FakeOperation> found) {
        return found.stream().findFirst().map(this::loadSplits);
    }

    private FakeOperation loadSplits(FakeOperation operation) {
        operation.setSplits(new java.util.ArrayList<>(jdbc.query(
                "SELECT * FROM fake_payment_splits WHERE operation_id = ? ORDER BY split_index",
                SPLIT, operation.getId())));
        return operation;
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static final RowMapper<FakeBinding> BINDING = (rs, n) -> FakeBinding.builder()
            .bindingId(rs.getString("binding_id"))
            .maskedPan(rs.getString("masked_pan"))
            .last4(rs.getString("last4"))
            .expiry(rs.getString("expiry"))
            .maskedPhone(rs.getString("masked_phone"))
            .cardHolder(rs.getString("card_holder"))
            .codeSentAt(time(rs, "code_sent_at"))
            .confirmedAt(time(rs, "confirmed_at"))
            .createdAt(time(rs, "created_at"))
            .build();

    private static final RowMapper<FakeCard> CARD = (rs, n) -> FakeCard.builder()
            .cardId(rs.getString("card_id"))
            .tokenHash(rs.getString("token_hash"))
            .maskedPan(rs.getString("masked_pan"))
            .last4(rs.getString("last4"))
            .expiry(rs.getString("expiry"))
            .cardHolder(rs.getString("card_holder"))
            .maskedPhone(rs.getString("masked_phone"))
            .balance(rs.getLong("balance"))
            .active(rs.getBoolean("is_active"))
            .createdAt(time(rs, "created_at"))
            .removedAt(time(rs, "removed_at"))
            .build();

    private static final RowMapper<FakeOperation> OPERATION = (rs, n) -> FakeOperation.builder()
            .id(rs.getString("id"))
            .kind(FakeOperation.Kind.valueOf(rs.getString("kind")))
            .account(rs.getString("account"))
            .cardId(rs.getString("card_id"))
            .last4(rs.getString("last4"))
            .otpRequired(rs.getBoolean("otp_required"))
            .amount(rs.getLong("amount"))
            .commission(rs.getLong("commission"))
            .status(ProviderStatus.valueOf(rs.getString("status")))
            .errorCode(rs.getString("error_code"))
            .holdTill(time(rs, "hold_till"))
            .succeededAt(time(rs, "succeeded_at"))
            .createdAt(time(rs, "created_at"))
            .updatedAt(time(rs, "updated_at"))
            .build();

    private static final RowMapper<FakeSplit> SPLIT = (rs, n) -> FakeSplit.builder()
            .transactionId(rs.getString("transaction_id"))
            .position(rs.getInt("split_index"))
            .recipientId(rs.getString("recipient_id"))
            .platform(rs.getBoolean("is_platform"))
            .amount(rs.getLong("amount"))
            .commission(rs.getLong("commission"))
            .successTransId(rs.getString("success_trans_id"))
            .reversedAt(time(rs, "reversed_at"))
            .build();

    private static final RowMapper<FakeReverse> REVERSE = (rs, n) -> FakeReverse.builder()
            .id(rs.getString("id"))
            .operationId(rs.getString("operation_id"))
            .transactionId(rs.getString("transaction_id"))
            .amount(rs.getLong("amount"))
            .outcome(FakeReverse.Outcome.valueOf(rs.getString("outcome")))
            .errorCode(rs.getString("error_code"))
            .reason(rs.getString("reason"))
            .createdAt(time(rs, "created_at"))
            .build();
}
