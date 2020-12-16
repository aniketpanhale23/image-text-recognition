import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static java.lang.System.exit;

public class ec2b {

    public static void main(String[] args) throws InterruptedException, IOException {
        // Credentials for AWS Account
        String aws_access_key_id = "KEY ID";
        String aws_secret_access_key = "YOUR KEY";
        String aws_session_token = "YOUR TOKEN";

        // Define variables
        String BUCKET_NAME = "YOUR IMAGES BUCKET";
        String QUEUE_NAME = "YOUR FIFO QUEUE";
        String OUTPUT_FILE = "output.txt";
        boolean END_OF_QUEUE = Boolean.FALSE;

        // Make a connection to the AWS Account
        AWSCredentials credentials = new BasicSessionCredentials(aws_access_key_id, aws_secret_access_key, aws_session_token);
        AWSStaticCredentialsProvider awsStaticCredentialsProvider = new AWSStaticCredentialsProvider(credentials);

        // Make a connection to the AWS Rekognition Service
        AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.standard()
                .withCredentials(awsStaticCredentialsProvider)
                .withRegion("us-east-1").build();

        // Make a connection to the AWS SQS Service
        AmazonSQS sqs = AmazonSQSClientBuilder.standard()
                .withCredentials(awsStaticCredentialsProvider)
                .withRegion("us-east-1").build();

        // Delete previous output file
        Files.deleteIfExists(Paths.get(OUTPUT_FILE));

        while(END_OF_QUEUE != Boolean.TRUE) {
            try {
                // Check if we have any messages available in the SQS FIFO Queue
                ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(QUEUE_NAME);
                List<Message> messages = sqs.receiveMessage(receiveMessageRequest)
                        .getMessages();
                Thread.sleep(1000);
                System.out.println("Waiting for messages in "+QUEUE_NAME+"...");

                for(Message message : messages) {
                    if(!message.getBody().equals("-1")) {

                        // Get the image from S3 and Detect Text
                        DetectTextRequest request = new DetectTextRequest()
                                .withImage(new Image()
                                        .withS3Object(new com.amazonaws.services.rekognition.model.S3Object()
                                                .withName(message.getBody())
                                                .withBucket(BUCKET_NAME)));
                        DetectTextResult result = rekognitionClient.detectText(request);
                        List<TextDetection> textDetections = result.getTextDetections();
                        System.out.println("Detected lines and words for " + message.getBody());

                        // Print and save the text in the output file
                        for (TextDetection text : textDetections) {
                            if(text.getType().equals("LINE")) {
                                String output = message.getBody() + " : " + text.getDetectedText() + "\n";
                                byte b[] = output.getBytes();
                                File outputFile = new File(OUTPUT_FILE);
                                outputFile.createNewFile();
                                FileOutputStream oFile = new FileOutputStream(outputFile, true);
                                oFile.write(b);
                                oFile.close();
                                System.out.print(output);
                            }
                        }

                        // Delete the message from the SQS FIFO Queue
                        System.out.println("Deleting " + message.getBody() + " from " + QUEUE_NAME + ".");
                        String messageReceiptHandle = message.getReceiptHandle();
                        sqs.deleteMessage(new DeleteMessageRequest(QUEUE_NAME, messageReceiptHandle));
                    } else {

                        // Delete the queue if we have reached the end of the SQS FIFO Queue (Message "-1")
                        System.out.println("Deleting "+QUEUE_NAME+".\nPlease wait for 60 seconds to run the scripts again...");
                        sqs.deleteQueue(new DeleteQueueRequest(QUEUE_NAME));

                        // Terminate the while loop
                        END_OF_QUEUE = Boolean.TRUE;
                    }
                }
            } catch (AmazonServiceException e) {
                if (e.getErrorCode().equals("AWS.SimpleQueueService.NonExistentQueue")) {
                    System.out.println("Queue " + QUEUE_NAME + " does not exist yet. Waiting for it to be created...");
                    Thread.sleep(5000);
                } else if (e.getErrorCode().equals("ExpiredToken")) {
                    System.out.println("Your credentials have expired. Please update aws_access_key_id, aws_secret_access_key and aws_session_token.");
                    exit(0);
                } else {
                    END_OF_QUEUE = Boolean.TRUE;
                    e.printStackTrace();
                }
            } catch(InterruptedException i){
                END_OF_QUEUE = Boolean.TRUE;
                i.printStackTrace();
            } catch (NullPointerException ignored) {} catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
