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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.streaming.StreamClient;
import com.oracle.bmc.streaming.requests.PutMessagesRequest;
import com.oracle.bmc.streaming.model.PutMessagesDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetailsEntry;
import com.oracle.bmc.streaming.requests.CreateGroupCursorRequest;
import com.oracle.bmc.streaming.model.CreateGroupCursorDetails.Type;
import com.oracle.bmc.streaming.model.CreateGroupCursorDetails;
import com.oracle.bmc.streaming.requests.GetMessagesRequest;
import com.oracle.bmc.streaming.model.Message;

@Command(name = "ClaimCheckClient",
        description = "Sample application which demonstrates Claim-Check Integration Pattern")
public class ClaimCheckClient implements Callable<Integer> {

    public final static String UTF16 = "UTF-8";

    final Logger logger = LoggerFactory.getLogger(ClaimCheckClient.class);

    static class ProducerArgs {
        @Option(names = "-producer", required = true) boolean isProducerMode;
        @Option(names = { "-b", "--bucket-name" }, required = true) String bucketName;
    }

    static class ConsumerArgs {
        @Option(names = "-consumer", required = true ) boolean isConsumerMode;
        @Option(names = { "-g", "--consumer-group" }, required = false) String consumerGroup = "all";
        @Option(names = { "-p", "--polling-interval" }, required = false) Integer pollingIntervalSecs = 2;
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

    @Parameters(index = "0") 
    private String filePathStr;

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

        var osNamespace = getObjectStorageNamespace(osClient); // OCI API Call inside

        // CONSUMER MODE
        if(clientModeArgs.consumerArgs != null && clientModeArgs.consumerArgs.isConsumerMode) {
            // Create Consumer Group Cursor
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
                    logger.error("GetMessages failed - HTTP {}", getMessagesResponseCode);
                    return 1;
                }
                String nextCursor = getMessagesResponse.getOpcNextCursor();
                List<Message> messages = getMessagesResponse.getItems();
                for(Message msg : messages) {
                    String osPathToObject = new String(msg.getValue(), UTF16);
                    logger.info("Successfully consumed message from the stream");
                    logger.info("Path to the object {}", osPathToObject);

                    Pattern p = Pattern.compile("^/n/(.+)/b/(.+)/o/(.+)$");
                    Matcher m = p.matcher(osPathToObject);
                    m.find();
                    String discoveredNamespace = m.group(1);
                    String discoveredBucket = m.group(2);
                    String discoveredObject = m.group(3);

                    // Get the object
                    // https://docs.oracle.com/en-us/iaas/api/#/en/objectstorage/20160918/Object/GetObject
                    var getObjectRequest = GetObjectRequest.builder()
                                            .namespaceName(discoveredNamespace)
                                            .bucketName(discoveredBucket)
                                            .objectName(discoveredObject)
                                            .build();

                    var getObjectResponse = osClient.getObject(getObjectRequest);
                    var getObjectResponseCode = getObjectResponse.get__httpStatusCode__();
                    if(getObjectResponseCode!=200) {
                        logger.error("GetObject failed - HTTP {}", getObjectResponseCode);
                        return 1;
                    }

                    var downloadedFilePath = Paths.get(filePathStr);
                    var isExisting = Files.exists(downloadedFilePath);
                    if(isExisting) {
                        logger.warn("{} already exists and will be replaced", downloadedFilePath );
                    }                
                    Files.copy(getObjectResponse.getInputStream(), downloadedFilePath, StandardCopyOption.REPLACE_EXISTING);
                    
                }
                cursor = nextCursor;
                Thread.sleep(clientModeArgs.consumerArgs.pollingIntervalSecs*1000);
            } while(true);
        }
        // PRODUCER MODE
        if(clientModeArgs.producerArgs != null && clientModeArgs.producerArgs.isProducerMode) {
            var filePath = Paths.get(filePathStr);

            // Check the file
            var isExisting = Files.exists(filePath);
            var isRegularFile = Files.isRegularFile(filePath);
            if(!isExisting || !isRegularFile) {
                logger.error("{} is not a regular file", filePath );
                return 1;
            }
            logger.info("Found file: {} ({} kB)", filePathStr, Files.size(filePath)/1024 );

            FileInputStream input = new FileInputStream(filePathStr);
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

    private String getObjectStorageNamespace(ObjectStorageClient osClient) {
        logger.info("Quering for Object Storage namespace"); 
        // Get Object Storage Namespace
        // https://docs.oracle.com/en-us/iaas/api/#/en/objectstorage/20160918/Namespace/GetNamespace
        var getNamespaceRequest = GetNamespaceRequest.builder()
                                    .compartmentId(compartmentId)
                                    .build();
        var getNamespaceResponse = osClient.getNamespace(getNamespaceRequest);
        var getNamespaceResponseCode = getNamespaceResponse.get__httpStatusCode__();
        if(getNamespaceResponseCode!=200) {
            logger.error("GetNamespace failed - HTTP {}", getNamespaceResponseCode);
            System.exit(1);
        }
        var osNamespace = getNamespaceResponse.getValue();
        logger.info("Your object storage namespace: {}", osNamespace);
        return osNamespace;
    }

    public static void main(String[] args) throws IOException {
        System.exit(new CommandLine(new ClaimCheckClient()).execute(args));
    }

}
