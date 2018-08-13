package uk.gov.hmcts.reform.bulkscanprocessor.model.mapper;

import org.junit.Before;
import org.junit.Test;
import uk.gov.hmcts.reform.bulkscanprocessor.entity.Classification;
import uk.gov.hmcts.reform.bulkscanprocessor.entity.Envelope;
import uk.gov.hmcts.reform.bulkscanprocessor.helper.EnvelopeCreator;
import uk.gov.hmcts.reform.bulkscanprocessor.helper.ToStringComparator;
import uk.gov.hmcts.reform.bulkscanprocessor.model.out.EnvelopeResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class EnvelopeResponseMapperTest {

    private Envelope envelope;

    @Before
    public void setUp() throws Exception {
        envelope = EnvelopeCreator.envelope();
    }

    @Test
    public void should_map_envelope_to_envelope_response() throws Exception {
        EnvelopeResponseMapper mapper = new EnvelopeResponseMapper();
        EnvelopeResponse response = mapper.toEnvelopeResponse(envelope);

        assertThat(response)
            .usingComparatorForFields(
                new ToStringComparator<Classification>(), new String[]{"classification"}
            )
            .isEqualToComparingFieldByFieldRecursively(envelope);
    }

}