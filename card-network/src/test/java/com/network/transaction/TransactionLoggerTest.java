package com.network.transaction;

import com.network.domain.Participant;
import com.network.domain.Transaction;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionLoggerTest {

    private final TransactionRepository repo   = Mockito.mock(TransactionRepository.class);
    private final TransactionLogger     logger = new TransactionLogger(repo);

    private IsoMessage mockMessage(String mcc) {
        IsoMessage msg = Mockito.mock(IsoMessage.class);
        when(msg.getStan()).thenReturn("000001");
        when(msg.getMti()).thenReturn("0100");
        when(msg.getProcessingCode()).thenReturn("000000");
        when(msg.getPan()).thenReturn("4111111111111111");
        when(msg.getAmount()).thenReturn("000000010000");
        when(msg.getCurrency()).thenReturn("840");
        when(msg.get(Field.RETRIEVAL_REF)).thenReturn("REF000000001");
        when(msg.get(Field.MCC)).thenReturn(mcc);
        return msg;
    }

    @Test
    void logIncoming_persistsMccFromDE18() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        logger.logIncoming(mockMessage("5411"),
                Mockito.mock(Participant.class),
                Mockito.mock(Participant.class));

        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getMcc()).isEqualTo("5411");
    }

    @Test
    void logIncoming_mccIsNull_whenDE18Absent() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        logger.logIncoming(mockMessage(null),
                Mockito.mock(Participant.class),
                Mockito.mock(Participant.class));

        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getMcc()).isNull();
    }

    @Test
    void logIncoming_setsStatusToPending() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        logger.logIncoming(mockMessage("5411"),
                Mockito.mock(Participant.class),
                Mockito.mock(Participant.class));

        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Transaction.Status.PENDING);
    }
}
