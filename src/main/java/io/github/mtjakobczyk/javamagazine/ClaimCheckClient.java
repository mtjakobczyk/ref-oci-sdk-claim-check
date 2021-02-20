package io.github.mtjakobczyk.javamagazine;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ArgGroup;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.streaming.StreamClient;
import com.oracle.bmc.streaming.requests.PutMessagesRequest;
import com.oracle.bmc.streaming.model.PutMessagesDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetailsEntry;
import com.oracle.bmc.streaming.requests.CreateGroupCursorRequest;
import com.oracle.bmc.streaming.model.CreateGroupCursorDetails.Type;
import com.oracle.bmc.streaming.model.CreateGroupCursorDetails;
import com.oracle.bmc.streaming.requests.GetMessagesRequest;
import com.oracle.bmc.streaming.model.Message;

@Command(name = "ClaimCheckClient")
public class ClaimCheckClient implements Callable<Integer> {

    public final static String UTF16 = "UTF-8";

    final Logger logger = LoggerFactory.getLogger("io.github.mtjakobczyk");

    static class ProducerArgs {
        @Option(names = "-producer", required = true ) boolean isProducerMode;
        @Option(names = { "-b", "--bucket-name" }, required = true) String bucketName;
        @Parameters(index = "0") private String filePathStr;
    }

    static class ConsumerArgs {
        @Option(names = "-consumer", required = true ) boolean isConsumerMode;
        @Option(names = { "-g", "--consumer-group" }, required = false) String consumerGroup = "all";
        @Option(names = { "-p", "--polling-interval" }, required = false) Integer pollingIntervalSecs = 2;
        @Parameters(index = "0") private String downloadedFilePathStr;
    }

    static class ClientModeArgs {
        @ArgGroup(exclusive = false, multiplicity = "1", heading = "Producer mode args%n")
        ProducerArgs producerArgs;
        @ArgGroup(exclusive = false, multiplicity = "1", heading = "Consumer mode args%n")
        ConsumerArgs consumerArgs;
    }

    @ArgGroup(exclusive = true, multiplicity = "1")
    ClientModeArgs clientModeArgs;

    @Option(names = { "-n", "--client-name" }) 
    private String clientName;

    @Option(names = { "-c", "--compartment-ocid" }) 
    private String compartmentId;

    @Option(names = { "-s", "--stream-ocid" }) 
    private String streamId;

    @Option(names = { "-e", "--stream-endpoint" }) 
    private String streamEndpoint;

    @Override
    public Integer call() throws IOException, InterruptedException {
        logger.info("Starting ClaimCheckClient");

        logger.info("Using DEFAULT profile from the default OCI configuration file ($HOME/.oci/config)");  
        var configFile = ConfigFileReader.parseDefault();
        var ociAuthProvider = new ConfigFileAuthenticationDetailsProvider(configFile);

        logger.info("Preparing OCI API clients (for Object Storage and Streaming)");  
        var osClient = ObjectStorageClient.builder().build(ociAuthProvider);
        var streamClient = StreamClient.builder()
                                .endpoint(streamEndpoint)
                                .build(ociAuthProvider);

        logger.info("Quering for Object Storage namespace"); 
        // Get Object Storage Namespace
        // https://docs.oracle.com/en-us/iaas/api/#/en/objectstorage/20160918/Namespace/GetNamespace
        var getNamespaceRequest = GetNamespaceRequest.builder()
                                    .compartmentId(compartmentId)
                                    .build();
        var getNamespaceResponse = osClient.getNamespace(getNamespaceRequest);
        var osNamespace = getNamespaceResponse.getValue();
        logger.info("Your object storage namespace: {}", osNamespace);

        if(clientModeArgs.consumerArgs != null && clientModeArgs.consumerArgs.isConsumerMode) {
            // Create Consumer Group Cursor
            // cursor type LATEST to consume messages published after cursor creation
            // https://docs.oracle.com/en-us/iaas/api/#/en/streaming/20180418/Cursor/CreateGroupCursor
            var createGroupCursorDetails = CreateGroupCursorDetails.builder()
                                            .groupName(clientModeArgs.consumerArgs.consumerGroup)
                                            .instanceName(clientName)
                                            .type(Type.Latest)
                                            .commitOnGet(true)
                                            .build();
            var createGroupCursorRequest = CreateGroupCursorRequest.builder()
                                            .streamId(streamId)
                                            .createGroupCursorDetails(createGroupCursorDetails)
                                            .build();
            var createGroupCursorResponse = streamClient.createGroupCursor(createGroupCursorRequest);
            int createGroupCursorResponseCode = createGroupCursorResponse.get__httpStatusCode__();
            if(createGroupCursorResponseCode != 200) {
                logger.error("CreateGroupCursor failed - HTTP {}", createGroupCursorResponseCode);
                return 1;
            }
            String cursor = createGroupCursorResponse.getCursor().getValue();

            do {
                // GetMessages
                // https://docs.oracle.com/en-us/iaas/api/#/en/streaming/20180418/Message/GetMessages
                var getMessagesRequest = GetMessagesRequest.builder()
                                            .streamId(streamId)
                                            .cursor(cursor)
                                            .limit(10)
                                            .build();
                var getMessagesResponse = streamClient.getMessages(getMessagesRequest);
                int getMessagesResponseCode = getMessagesResponse.get__httpStatusCode__();
                if(getMessagesResponseCode != 200) {
                    logger.error("GetMessages failed - HTTP {}", createGroupCursorResponseCode);
                    return 1;
                }
                String nextCursor = getMessagesResponse.getOpcNextCursor();
                List<Message> messages = getMessagesResponse.getItems();
                for(Message msg : messages) {
                    String osPathToObject = new String(msg.getValue(), UTF16);
                    logger.info("Successfully consumed message from the stream");
                    logger.info("Path to the object {}", osPathToObject);
                }
                cursor = nextCursor;
                Thread.sleep(clientModeArgs.consumerArgs.pollingIntervalSecs*1000);
            } while(true);

            
        }
        if(clientModeArgs.producerArgs != null && clientModeArgs.producerArgs.isProducerMode) {
            var filePath = Paths.get(clientModeArgs.producerArgs.filePathStr);

            // Check the file
            var isExisting = Files.exists(filePath);
            var isRegularFile = Files.isRegularFile(filePath);
            if(!isExisting || !isRegularFile) {
                logger.error("{} is not a regular file", filePath );
                return 1;
            }
            logger.info("Found file: {} ({} kB)", clientModeArgs.producerArgs.filePathStr, Files.size(filePath)/1024 );

            FileInputStream input = new FileInputStream(clientModeArgs.producerArgs.filePathStr);
            try(input) {
                var objectName = filePath.getFileName().toString();
                var osPathToObject = "/n/"+osNamespace+"/b/"+clientModeArgs.producerArgs.bucketName+"/o/"+objectName;
                logger.info("Uploading the file as {}", osPathToObject);
                // Upload the object
                // https://docs.oracle.com/en-us/iaas/api/#/en/objectstorage/20160918/Object/PutObject
                var putObjectRequest = PutObjectRequest.builder()
                                            .namespaceName(osNamespace)
                                            .bucketName(clientModeArgs.producerArgs.bucketName)
                                            .objectName(objectName)
                                            .putObjectBody(input)
                                            .build();
                var putObjectResponse = osClient.putObject(putObjectRequest);

                if(putObjectResponse.get__httpStatusCode__()==200) {
                    logger.info("Successfully uploaded the file");

                    // Prepare PutMessagesDetails
                    var messages = new ArrayList<PutMessagesDetailsEntry>();
                    messages.add(PutMessagesDetailsEntry.builder()
                                    .value(osPathToObject.getBytes(UTF16))
                                    .build());
                    var putMessagesDetails = PutMessagesDetails.builder().messages(messages).build();
                    
                    // Put message to the stream
                    // https://docs.oracle.com/en-us/iaas/api/#/en/streaming/20180418/Message/PutMessages
                    var putMessagesRequest = PutMessagesRequest.builder()
                                                .streamId(streamId)
                                                .putMessagesDetails(putMessagesDetails)
                                                .build();
                    var putMessagesResponse = streamClient.putMessages(putMessagesRequest);

                    if(putMessagesResponse.get__httpStatusCode__()==200)
                        logger.info("Successfully published the message to the stream");
                }
            }
        }
        return 0;
    }

    public static void main(String[] args) throws IOException {
        System.exit(new CommandLine(new ClaimCheckClient()).execute(args));
    }

}
