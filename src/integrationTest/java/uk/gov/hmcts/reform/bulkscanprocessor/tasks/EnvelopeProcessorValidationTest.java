package uk.gov.hmcts.reform.bulkscanprocessor.tasks;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.FileNameIrregularitiesException;
import uk.gov.hmcts.reform.bulkscanprocessor.helper.EnvelopeCreator;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.EnvelopeProcessor;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.ZipFileProcessor;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.ZipInputStream;

import static com.google.common.io.Resources.getResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * This is unit test. Falls under integration to make use of existing zip file resources.
 */
@RunWith(SpringRunner.class)
public class EnvelopeProcessorValidationTest {

    private static final EnvelopeProcessor envelopeProcessor = new EnvelopeProcessor(
        null,
        null,
        null,
        1,
        1) {
    };


    @Test
    public void should_throw_exception_when_zip_file_contains_fewer_pdfs() throws IOException, URISyntaxException {
        ZipFileProcessor zipFileProcessor = getZipFileProcessor("4_24-06-2018-00-00-00.zip");

        Throwable throwable = catchThrowable(() ->
            envelopeProcessor.assertEnvelopeHasPdfs(
                zipFileProcessor.getEnvelope(),
                zipFileProcessor.getPdfs()
            )
        );

        assertThat(throwable).isInstanceOf(FileNameIrregularitiesException.class)
            .hasMessageMatching("Missing PDFs: 1111001.pdf");
    }

    @Test
    public void should_throw_exception_when_zip_file_contains_more_pdfs() throws IOException, URISyntaxException {
        ZipFileProcessor zipFileProcessor = getZipFileProcessor("9_24-06-2018-00-00-00.zip");

        Throwable throwable = catchThrowable(() ->
            envelopeProcessor.assertEnvelopeHasPdfs(
                zipFileProcessor.getEnvelope(),
                zipFileProcessor.getPdfs()
            )
        );

        assertThat(throwable).isInstanceOf(FileNameIrregularitiesException.class)
            .hasMessageMatching("Missing PDFs: 1111002.pdf");
    }

    @Test
    public void should_throw_exception_when_zip_file_has_mismatching_pdf() throws IOException, URISyntaxException {
        ZipFileProcessor zipFileProcessor = getZipFileProcessor("8_24-06-2018-00-00-00.zip");

        Throwable throwable = catchThrowable(() ->
            envelopeProcessor.assertEnvelopeHasPdfs(
                zipFileProcessor.getEnvelope(),
                zipFileProcessor.getPdfs()
            )
        );

        assertThat(throwable).isInstanceOf(FileNameIrregularitiesException.class)
            .hasMessage("Missing PDFs: 1111001.pdf, 1111005.pdf");
    }

    private ZipFileProcessor getZipFileProcessor(String zipFileName) throws IOException, URISyntaxException {
        ZipFileProcessor processor = new ZipFileProcessor("container", zipFileName);

        try (ZipInputStream zis = getZipInputStream(zipFileName)) {
            processor.process(zis);
            processor.setEnvelope(EnvelopeCreator.getEnvelopeFromMetafile(processor.getMetadata()));
        }

        return processor;
    }

    private ZipInputStream getZipInputStream(String zipFileName) throws IOException, URISyntaxException {
        return new ZipInputStream(Files.newInputStream(Paths.get(getResource(zipFileName).toURI())));
    }
}