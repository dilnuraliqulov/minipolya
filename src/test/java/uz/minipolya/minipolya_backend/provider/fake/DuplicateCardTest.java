package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Item 5: a card the player already has is a 409 before ATMOS is asked, never "service down". */
@DatabaseTest
class DuplicateCardTest extends FakeProviderFlowSupport {

    private ResultActions start(User user, String number, String expiry) throws Exception {
        return mvc.perform(post("/api/cards").header("Authorization", data.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardNumber\":\"" + number + "\",\"expiry\":\"" + expiry + "\"}"));
    }

    @Test
    void theSameCardTwiceIs409() throws Exception {
        User player = data.user("Duplicate Card");
        addCard(player, "8600123412347777");

        start(player, "8600123412347777", "12/29")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_ALREADY_ADDED"))
                .andExpect(jsonPath("$.message").value("Bu karta allaqachon qoʻshilgan"));
    }

    @Test
    void anotherExpiryAnotherPlayerOrADeletedCardIsNotADuplicate() throws Exception {
        User player = data.user("Duplicate Card Two");
        UUID card = addCard(player, "8600123412348888");

        start(player, "8600123412348888", "11/30").andExpect(status().isOk());
        start(data.user("Someone Else"), "8600123412348888", "12/29").andExpect(status().isOk());

        mvc.perform(delete("/api/cards/" + card).header("Authorization", data.bearer(player)))
                .andExpect(status().isOk());
        start(player, "8600123412348888", "12/29").andExpect(status().isOk());
    }
}
