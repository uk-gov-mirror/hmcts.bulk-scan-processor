package uk.gov.hmcts.reform.bulkscanprocessor.tasks;

import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobInputStream;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.bulkscanprocessor.entity.Envelope;
import uk.gov.hmcts.reform.bulkscanprocessor.entity.EnvelopeRepository;
import uk.gov.hmcts.reform.bulkscanprocessor.entity.ProcessEventRepository;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.DocSignatureFailureException;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.FileNameIrregularitiesException;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.InvalidEnvelopeSchemaException;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.MetadataNotFoundException;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.NonPdfFileFoundException;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.OcrDataParseException;
import uk.gov.hmcts.reform.bulkscanprocessor.exceptions.PreviouslyFailedToUploadException;
import uk.gov.hmcts.reform.bulkscanprocessor.model.blob.InputEnvelope;
import uk.gov.hmcts.reform.bulkscanprocessor.model.common.Event;
import uk.gov.hmcts.reform.bulkscanprocessor.model.out.msg.ErrorMsg;
import uk.gov.hmcts.reform.bulkscanprocessor.services.errornotifications.ErrorMapping;
import uk.gov.hmcts.reform.bulkscanprocessor.services.servicebus.ServiceBusHelper;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.BlobManager;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.DocumentProcessor;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.EnvelopeProcessor;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.ZipFileProcessingResult;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.ZipFileProcessor;
import uk.gov.hmcts.reform.bulkscanprocessor.tasks.processor.ZipVerifiers;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.Date;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static uk.gov.hmcts.reform.bulkscanprocessor.model.mapper.EnvelopeMapper.toDbEnvelope;

/**
 * This class is a task executed by Scheduler as per configured interval.
 * It will read all the blobs from Azure Blob storage and will do below things:
 * <ol>
 * <li>Read Blob from container by acquiring lease</li>
 * <li>Extract Zip file (blob)</li>
 * <li>Transform metadata json to DB entities</li>
 * <li>Save PDF files in document storage</li>
 * <li>Update status and doc urls in DB</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(value = "scheduling.task.scan.enabled", matchIfMissing = true)
public class BlobProcessorTask extends Processor {

    private static final Logger log = LoggerFactory.getLogger(BlobProcessorTask.class);

    @Value("${storage.blob_processing_delay_in_minutes}")
    protected int blobProcessingDelayInMinutes;

    private final ServiceBusHelper notificationsQueueHelper;

    @Autowired
    public BlobProcessorTask(
        BlobManager blobManager,
        DocumentProcessor documentProcessor,
        EnvelopeProcessor envelopeProcessor,
        EnvelopeRepository envelopeRepository,
        ProcessEventRepository eventRepository,
        @Qualifier("notifications") ServiceBusHelper notificationsQueueHelper
    ) {
        super(blobManager, documentProcessor, envelopeProcessor, envelopeRepository, eventRepository);
        this.notificationsQueueHelper = notificationsQueueHelper;
    }

    // NOTE: this is needed for testing as children of this class are instantiated
    // using "new" in tests despite being spring beans (sigh!)
    @SuppressWarnings("squid:S00107")
    public BlobProcessorTask(
        BlobManager blobManager,
        DocumentProcessor documentProcessor,
        EnvelopeProcessor envelopeProcessor,
        EnvelopeRepository envelopeRepository,
        ProcessEventRepository eventRepository,
        @Qualifier("notifications") ServiceBusHelper notificationsQueueHelper,
        String signatureAlg,
        String publicKeyDerFilename
    ) {
        this(
            blobManager,
            documentProcessor,
            envelopeProcessor,
            envelopeRepository,
            eventRepository,
            notificationsQueueHelper
        );
        this.signatureAlg = signatureAlg;
        this.publicKeyDerFilename = publicKeyDerFilename;
    }

    @Scheduled(fixedDelayString = "${scheduling.task.scan.delay}")
    public void processBlobs() throws IOException, StorageException, URISyntaxException {
        for (CloudBlobContainer container : blobManager.listInputContainers()) {
            processZipFiles(container);
        }
    }

    private void processZipFiles(CloudBlobContainer container)
        throws IOException, StorageException, URISyntaxException {
        log.info("Processing blobs for container {}", container.getName());

        // Randomise iteration order to minimise lease acquire contention
        // For this purpose it's more efficient to have a collection that
        // implements RandomAccess (e.g. ArrayList)
        List<String> zipFilenames = new ArrayList<>();
        container.listBlobs().forEach(
            b -> zipFilenames.add(FilenameUtils.getName(b.getUri().toString()))
        );
        Collections.shuffle(zipFilenames);
        for (String zipFilename : zipFilenames) {
            processZipFile(container, zipFilename);
        }
    }

    private void processZipFile(CloudBlobContainer container, String zipFilename)
        throws IOException, StorageException, URISyntaxException {

        CloudBlockBlob cloudBlockBlob = container.getBlockBlobReference(zipFilename);
        cloudBlockBlob.downloadAttributes();

        if (!isReadyToBeProcessed(cloudBlockBlob)) {
            return;
        }

        log.info("Processing zip file {}", zipFilename);

        Envelope existingEnvelope =
            envelopeProcessor.getEnvelopeByFileAndContainer(container.getName(), zipFilename);
        if (existingEnvelope != null) {
            deleteIfProcessed(cloudBlockBlob, existingEnvelope);
            return;
        }

        Optional<String> leaseId = blobManager.acquireLease(cloudBlockBlob, container.getName(), zipFilename);

        if (leaseId.isPresent()) {
            BlobInputStream blobInputStream = cloudBlockBlob.openInputStream();

            // Zip file will include metadata.json and collection of pdf documents
            try (ZipInputStream zis = new ZipInputStream(blobInputStream)) {
                ZipFileProcessingResult processingResult =
                    processZipFileContent(zis, zipFilename, container.getName(), leaseId.get());

                if (processingResult != null) {
                    processParsedEnvelopeDocuments(
                        processingResult.getEnvelope(),
                        processingResult.getPdfs(),
                        cloudBlockBlob
                    );
                }
            }
        }
    }

    private void deleteIfProcessed(CloudBlockBlob cloudBlockBlob, Envelope envelope) {
        try {
            if (cloudBlockBlob != null && envelope.getStatus().isProcessed()) {
                boolean deleted;
                if (cloudBlockBlob.exists()) {
                    deleted = cloudBlockBlob.deleteIfExists();
                } else {
                    deleted = true;
                }
                if (deleted) {
                    envelope.setZipDeleted(true);
                    envelopeProcessor.saveEnvelope(envelope);
                }
            }
        } catch (StorageException e) {
            log.warn("Failed to delete blob [{}]", cloudBlockBlob.getName());
        } // Do not propagate exception as this blob has already been marked with a delete failure
    }

    private ZipFileProcessingResult processZipFileContent(
        ZipInputStream zis,
        String zipFilename,
        String containerName,
        String leaseId
    ) {
        try {
            ZipFileProcessor zipFileProcessor = new ZipFileProcessor(); // todo: inject
            ZipVerifiers.ZipStreamWithSignature zipWithSignature =
                ZipVerifiers.ZipStreamWithSignature.fromKeyfile(zis, publicKeyDerFilename, zipFilename, containerName);

            ZipFileProcessingResult result = zipFileProcessor.process(
                zipWithSignature,
                ZipVerifiers.getPreprocessor(signatureAlg)
            );

            InputEnvelope envelope = envelopeProcessor.parseEnvelope(result.getMetadata(), zipFilename);

            EnvelopeProcessor.assertEnvelopeHasPdfs(envelope, result.getPdfs());
            envelopeProcessor.assertDidNotFailToUploadBefore(envelope.zipFileName, containerName);

            result.setEnvelope(envelopeProcessor.saveEnvelope(toDbEnvelope(envelope, containerName)));

            return result;
        } catch (InvalidEnvelopeSchemaException
            | FileNameIrregularitiesException
            | NonPdfFileFoundException
            | OcrDataParseException
            | MetadataNotFoundException ex
        ) {
            handleInvalidFileError(Event.FILE_VALIDATION_FAILURE, containerName, zipFilename, leaseId, ex);
            return null;
        } catch (DocSignatureFailureException ex) {
            handleInvalidFileError(Event.DOC_SIGNATURE_FAILURE, containerName, zipFilename, leaseId, ex);
            return null;
        } catch (PreviouslyFailedToUploadException ex) {
            handleEventRelatedError(Event.DOC_UPLOAD_FAILURE, containerName, zipFilename, ex);
            return null;
        } catch (Exception ex) {
            handleEventRelatedError(Event.DOC_FAILURE, containerName, zipFilename, ex);
            return null;
        }
    }

    private boolean isReadyToBeProcessed(CloudBlockBlob blob) {
        java.util.Date cutoff = Date.from(Instant.now().minus(this.blobProcessingDelayInMinutes, ChronoUnit.MINUTES));
        return blob.getProperties().getLastModified().before(cutoff);
    }

    private void handleInvalidFileError(
        Event fileValidationFailure,
        String containerName,
        String zipFilename,
        String leaseId,
        Exception cause
    ) {
        Long eventId = handleEventRelatedError(fileValidationFailure, containerName, zipFilename, cause);
        ErrorMapping
            .getFor(cause.getClass())
            .ifPresent(errorCode -> {
                try {
                    this.notificationsQueueHelper.sendMessage(
                        new ErrorMsg(
                            UUID.randomUUID().toString(),
                            eventId,
                            zipFilename,
                            containerName,
                            null,
                            null,
                            errorCode,
                            cause.getMessage(),
                            false
                        )
                    );
                } catch (Exception exc) {
                    log.error(
                        "Error sending notification to the queue."
                            + "File name: " + zipFilename + " "
                            + "Container: " + containerName,
                        exc
                    );
                }
            });

        blobManager.tryMoveFileToRejectedContainer(zipFilename, containerName, leaseId);
    }
}
