package com.github.ambry.messageformat;

import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Crc32;
import com.github.ambry.utils.CrcInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.io.InputStream;
import java.io.IOException;


/**
 * Represents the message format of the individual records that are used to write a message to the store.
 * This class provides the serialization and deserialization methods for the individual records.
 * The MessageFormatInputStream classes defines the complete message using these individual records.
 */
public class MessageFormatRecord {

  // Common info for all formats
  public static final int Version_Field_Size_In_Bytes = 2;
  public static final int Crc_Size = 8;

  public static final short Message_Header_Version_V1 = 1;
  public static final short BlobProperties_Version_V1 = 1;
  public static final short Delete_Version_V1 = 1;
  public static final short UserMetadata_Version_V1 = 1;
  public static final short Blob_Version_V1 = 1;
  public static final int Message_Header_Invalid_Relative_Offset = -1;

  // Deserialization methods for individual records
  public static BlobProperties deserializeBlobProperties(InputStream stream)
      throws IOException, MessageFormatException {
    CrcInputStream crcStream = new CrcInputStream(stream);
    DataInputStream inputStream = new DataInputStream(crcStream);
    short version = inputStream.readShort();
    switch (version) {
      case BlobProperties_Version_V1:
        return BlobProperties_Format_V1.deserializeBlobPropertiesRecord(crcStream);
      default:
        throw new MessageFormatException("blob property version not supported",
            MessageFormatErrorCodes.Unknown_Format_Version);
    }
  }

  public static boolean deserializeDeleteRecord(InputStream stream)
      throws IOException, MessageFormatException {
    CrcInputStream crcStream = new CrcInputStream(stream);
    DataInputStream inputStream = new DataInputStream(crcStream);
    short version = inputStream.readShort();
    switch (version) {
      case Delete_Version_V1:
        return Delete_Format_V1.deserializeDeleteRecord(crcStream);
      default:
        throw new MessageFormatException("delete record version not supported",
            MessageFormatErrorCodes.Unknown_Format_Version);
    }
  }

  public static ByteBuffer deserializeUserMetadata(InputStream stream)
      throws IOException, MessageFormatException {
    CrcInputStream crcStream = new CrcInputStream(stream);
    DataInputStream inputStream = new DataInputStream(crcStream);
    short version = inputStream.readShort();
    switch (version) {
      case UserMetadata_Version_V1:
        return UserMetadata_Format_V1.deserializeUserMetadataRecord(crcStream);
      default:
        throw new MessageFormatException("metadata version not supported",
            MessageFormatErrorCodes.Unknown_Format_Version);
    }
  }

  public static BlobOutput deserializeBlob(InputStream stream)
      throws IOException, MessageFormatException {
    CrcInputStream crcStream = new CrcInputStream(stream);
    DataInputStream inputStream = new DataInputStream(crcStream);
    short version = inputStream.readShort();
    switch (version) {
      case Blob_Version_V1:
        return Blob_Format_V1.deserializeBlobRecord(crcStream);
      default:
        throw new MessageFormatException("data version not supported", MessageFormatErrorCodes.Unknown_Format_Version);
    }
  }

  /**
   *  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
   * |         |                 |                 |                 |                 |                 |            |
   * | version |  payload size   | Blob Property   |     Delete      |  User Metadata  |      Blob       |    Crc     |
   * |(2 bytes)|   (8 bytes)     | Relative Offset | Relative Offset | Relative Offset | Relative Offset |  (8 bytes) |
   * |         |                 |   (4 bytes)     |   (4 bytes)     |   (4 bytes)     |   (4 bytes)     |            |
   *  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
   *  version         - The version of the message header
   *
   *  payload size    - The size of the message payload.
   *                    (Blob prop record size or delete record size) + user metadata size + blob size
   *
   *  blob property   - The offset at which the blob property record is located relative to this message. Only one of
   *  relative offset   blob property/delete relative offset field can exist. Non existence is indicated by -1
   *
   *  delete          - The offset at which the delete record is located relative to this message. Only one of blob
   *  relative offset   property/delete relative offset field can exist. Non existence is indicated by -1
   *
   *  user metadata   - The offset at which the user metadata record is located relative to this message. This exist
   *  relative offset   only when blob property record and blob record exist
   *
   *  blob metadata   - The offset at which the blob record is located relative to this message. This exist only when
   *  relative offset   blob property record and user metadata record exist
   *
   *  crc             - The crc of the message header
   *
   */
  public static class MessageHeader_Format_V1 {
    private ByteBuffer buffer;

    // total size field start offset and size
    public static final int Total_Size_Field_Offset_In_Bytes = Version_Field_Size_In_Bytes;
    public static final int Total_Size_Field_Size_In_Bytes = 8;

    // relative offset fields start offset and size
    private static final int Number_Of_Relative_Offset_Fields = 4;
    public static final int Relative_Offset_Field_Sizes_In_Bytes = 4;
    public static final int BlobProperties_Relative_Offset_Field_Offset_In_Bytes =
        Total_Size_Field_Offset_In_Bytes + Total_Size_Field_Size_In_Bytes;
    public static final int Delete_Relative_Offset_Field_Offset_In_Bytes =
        BlobProperties_Relative_Offset_Field_Offset_In_Bytes + Relative_Offset_Field_Sizes_In_Bytes;
    public static final int UserMetadata_Relative_Offset_Field_Offset_In_Bytes =
        Delete_Relative_Offset_Field_Offset_In_Bytes + Relative_Offset_Field_Sizes_In_Bytes;
    public static final int Blob_Relative_Offset_Field_Offset_In_Bytes =
        UserMetadata_Relative_Offset_Field_Offset_In_Bytes + Relative_Offset_Field_Sizes_In_Bytes;

    // crc field start offset
    public static final int Crc_Field_Offset_In_Bytes =
        Blob_Relative_Offset_Field_Offset_In_Bytes + Relative_Offset_Field_Sizes_In_Bytes;

    public static int getHeaderSize() {
      return Version_Field_Size_In_Bytes +
          Total_Size_Field_Size_In_Bytes +
          (Number_Of_Relative_Offset_Fields * Relative_Offset_Field_Sizes_In_Bytes) +
          Crc_Size;
    }

    public static void serializeHeader(ByteBuffer outputBuffer, long totalSize, int blobPropertiesRecordRelativeOffset,
        int deleteRecordRelativeOffset, int userMetadataRecordRelativeOffset, int blobRecordRelativeOffset)
        throws MessageFormatException {
      checkHeaderConstraints(totalSize, blobPropertiesRecordRelativeOffset, deleteRecordRelativeOffset,
          userMetadataRecordRelativeOffset, blobRecordRelativeOffset);
      int startOffset = outputBuffer.position();
      outputBuffer.putShort(Message_Header_Version_V1);
      outputBuffer.putLong(totalSize);
      outputBuffer.putInt(blobPropertiesRecordRelativeOffset);
      outputBuffer.putInt(deleteRecordRelativeOffset);
      outputBuffer.putInt(userMetadataRecordRelativeOffset);
      outputBuffer.putInt(blobRecordRelativeOffset);
      Crc32 crc = new Crc32();
      crc.update(outputBuffer.array(), startOffset, getHeaderSize() - Crc_Size);
      outputBuffer.putLong(crc.getValue());
      Logger logger = LoggerFactory.getLogger("MessageHeader_Format_V1");
      logger.trace("serializing header : version {} size {} blobpropertiesrecordrelativeoffset {} " +
          "deleterecordrelativeoffset {} " + "usermetadatarecordrelativeoffset {} blobrecordrelativeoffset {} crc {}",
          Message_Header_Version_V1, totalSize, blobPropertiesRecordRelativeOffset, deleteRecordRelativeOffset,
          userMetadataRecordRelativeOffset, blobPropertiesRecordRelativeOffset, crc.getValue());
    }

    // checks the following constraints
    // 1. totalSize is greater than 0
    // 2. if blobPropertiesRecordRelativeOffset is greater than 0, ensures that deleteRecordRelativeOffset
    //    is set to Message_Header_Invalid_Relative_Offset and userMetadataRecordRelativeOffset
    //    and blobRecordRelativeOffset is positive
    // 3. if deleteRecordRelativeOffset is greater than 0, ensures that all the other offsets are set to
    //    Message_Header_Invalid_Relative_Offset
    private static void checkHeaderConstraints(long totalSize, int blobPropertiesRecordRelativeOffset,
        int deleteRecordRelativeOffset, int userMetadataRecordRelativeOffset, int blobRecordRelativeOffset)
        throws MessageFormatException {
      // check constraints
      if (totalSize <= 0) {
        throw new MessageFormatException("checkHeaderConstraints - totalSize " + totalSize +
            " needs to be greater than 0", MessageFormatErrorCodes.Header_Constraint_Error);
      }

      if (blobPropertiesRecordRelativeOffset > 0 && (
          deleteRecordRelativeOffset != Message_Header_Invalid_Relative_Offset ||
              userMetadataRecordRelativeOffset <= 0 || blobRecordRelativeOffset <= 0)) {
        throw new MessageFormatException(
            "checkHeaderConstraints - blobPropertiesRecordRelativeOffset is greater than 0 " +
                " but other properties do not satisfy constraints" +
                " blobPropertiesRecordRelativeOffset " + blobPropertiesRecordRelativeOffset +
                " deleteRecordRelativeOffset " + deleteRecordRelativeOffset +
                " userMetadataRecordRelativeOffset " + userMetadataRecordRelativeOffset +
                " blobRecordRelativeOffset " + blobRecordRelativeOffset,
            MessageFormatErrorCodes.Header_Constraint_Error);
      }

      if (deleteRecordRelativeOffset > 0 && (
          blobPropertiesRecordRelativeOffset != Message_Header_Invalid_Relative_Offset ||
              userMetadataRecordRelativeOffset != Message_Header_Invalid_Relative_Offset ||
              blobRecordRelativeOffset != Message_Header_Invalid_Relative_Offset)) {
        throw new MessageFormatException("checkHeaderConstraints - deleteRecordRelativeOffset is greater than 0 " +
            " but other properties do not satisfy constraints" +
            " blobPropertiesRecordRelativeOffset " + blobPropertiesRecordRelativeOffset +
            " deleteRecordRelativeOffset " + deleteRecordRelativeOffset +
            " userMetadataRecordRelativeOffset " + userMetadataRecordRelativeOffset +
            " blobRecordRelativeOffset " + blobRecordRelativeOffset, MessageFormatErrorCodes.Header_Constraint_Error);
      }
    }

    public MessageHeader_Format_V1(ByteBuffer input) {
      this.buffer = input;
    }

    public short getVersion() {
      return buffer.getShort(0);
    }

    public long getMessageSize() {
      return buffer.getLong(Total_Size_Field_Offset_In_Bytes);
    }

    public int getBlobPropertiesRecordRelativeOffset() {
      return buffer.getInt(BlobProperties_Relative_Offset_Field_Offset_In_Bytes);
    }

    public int getDeleteRecordRelativeOffset() {
      return buffer.getInt(Delete_Relative_Offset_Field_Offset_In_Bytes);
    }

    public int getUserMetadataRecordRelativeOffset() {
      return buffer.getInt(UserMetadata_Relative_Offset_Field_Offset_In_Bytes);
    }

    public int getBlobRecordRelativeOffset() {
      return buffer.getInt(Blob_Relative_Offset_Field_Offset_In_Bytes);
    }

    public long getCrc() {
      return buffer.getLong(Crc_Field_Offset_In_Bytes);
    }

    public void verifyHeader()
        throws MessageFormatException {
      verifyCrc();
      checkHeaderConstraints(getMessageSize(), getBlobPropertiesRecordRelativeOffset(), getDeleteRecordRelativeOffset(),
          getUserMetadataRecordRelativeOffset(), getBlobRecordRelativeOffset());
    }

    private void verifyCrc()
        throws MessageFormatException {
      Crc32 crc = new Crc32();
      crc.update(buffer.array(), 0, buffer.limit() - Crc_Size);
      if (crc.getValue() != getCrc()) {
        throw new MessageFormatException("Message header is corrupt", MessageFormatErrorCodes.Data_Corrupt);
      }
    }
  }

  /**
   *  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
   * |         |               |               |           |            |
   * | version |   property1   |   property2   |           |     Crc    |
   * |(2 bytes)| (1 - n bytes) | (1 - n bytes) |   .....   |  (8 bytes) |
   * |         |               |               |           |            |
   *  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
   *  version         - The version of the blob property record
   *
   *  properties      - Variable size properties that define the blob.
   *
   *  crc             - The crc of the blob property record
   *
   */
  public static class BlobProperties_Format_V1 {

    private static Logger logger = LoggerFactory.getLogger(BlobProperties_Format_V1.class);

    public static int getBlobPropertiesRecordSize(BlobProperties properties) {
      return Version_Field_Size_In_Bytes +
          BlobPropertiesSerDe.getBlobPropertiesSize(properties) +
          Crc_Size;
    }

    public static void serializeBlobPropertiesRecord(ByteBuffer outputBuffer, BlobProperties properties) {
      int startOffset = outputBuffer.position();
      outputBuffer.putShort(BlobProperties_Version_V1);
      BlobPropertiesSerDe.putBlobPropertiesToBuffer(outputBuffer, properties);
      Crc32 crc = new Crc32();
      crc.update(outputBuffer.array(), startOffset, getBlobPropertiesRecordSize(properties) - Crc_Size);
      outputBuffer.putLong(crc.getValue());
    }

    public static BlobProperties deserializeBlobPropertiesRecord(CrcInputStream crcStream)
        throws IOException, MessageFormatException {
      try {
        DataInputStream dataStream = new DataInputStream(crcStream);
        BlobProperties properties = BlobPropertiesSerDe.getBlobPropertiesFromStream(dataStream);
        long actualCRC = crcStream.getValue();
        long expectedCRC = dataStream.readLong();
        if (actualCRC != expectedCRC) {
          logger.error(
              "corrupt data while parsing blob properties Expected CRC " + expectedCRC + " Actual CRC " + actualCRC);
          throw new MessageFormatException("Blob property data is corrupt", MessageFormatErrorCodes.Data_Corrupt);
        }
        return properties;
      } catch (Exception e) {
        logger.error("Blob property failed to be parsed. Data may be corrupt with exception {}", e);
        throw new MessageFormatException("Blob property failed to be parsed. Data may be corrupt",
            MessageFormatErrorCodes.Data_Corrupt);
      }
    }
  }

  /**
   *  - - - - - - - - - - - - - - - - - - -
   * |         |               |            |
   * | version |   delete byte |    Crc     |
   * |(2 bytes)|    (1 byte)   |  (8 bytes) |
   * |         |               |            |
   *  - - - - - - - - - - - - - - - - - - -
   *  version         - The version of the delete record
   *
   *  delete byte     - Takes value 0 or 1. If it is set to 1, it signifies that the blob is deleted. The field
   *                    is required to be able to support undelete in the future if required.
   *
   *  crc             - The crc of the delete record
   *
   */
  public static class Delete_Format_V1 {

    public static final int Delete_Field_Size_In_Bytes = 1;
    private static Logger logger = LoggerFactory.getLogger(Delete_Format_V1.class);

    public static int getDeleteRecordSize() {
      return Version_Field_Size_In_Bytes +
          Delete_Field_Size_In_Bytes +
          Crc_Size;
    }

    public static void serializeDeleteRecord(ByteBuffer outputBuffer, boolean deleteFlag) {
      int startOffset = outputBuffer.position();
      outputBuffer.putShort(Delete_Version_V1);
      outputBuffer.put(deleteFlag ? (byte) 1 : (byte) 0);
      Crc32 crc = new Crc32();
      crc.update(outputBuffer.array(), startOffset, getDeleteRecordSize() - Crc_Size);
      outputBuffer.putLong(crc.getValue());
    }

    public static boolean deserializeDeleteRecord(CrcInputStream crcStream)
        throws IOException, MessageFormatException {
      DataInputStream dataStream = new DataInputStream(crcStream);
      boolean isDeleted = dataStream.readByte() == 1 ? true : false;
      long actualCRC = crcStream.getValue();
      long expectedCRC = dataStream.readLong();
      if (actualCRC != expectedCRC) {
        logger
            .error("corrupt data while parsing delete record Expected CRC " + expectedCRC + " Actual CRC " + actualCRC);
        throw new MessageFormatException("delete record data is corrupt", MessageFormatErrorCodes.Data_Corrupt);
      }
      return isDeleted;
    }
  }

  /**
   *  - - - - - - - - - - - - - - - - - - - - - - - -
   * |         |           |            |            |
   * | version |   size    |  content   |     Crc    |
   * |(2 bytes)| (4 bytes) |  (n bytes) |  (8 bytes) |
   * |         |           |            |            |
   *  - - - - - - - - - - - - - - - - - - - - - - - -
   *  version    - The version of the user metadata record
   *
   *  size       - The size of the user metadata content
   *
   *  content    - The actual content that represents the user metadata
   *
   *  crc        - The crc of the user metadata record
   *
   */
  public static class UserMetadata_Format_V1 {
    public static final int UserMetadata_Size_Field_In_Bytes = 4;
    private static Logger logger = LoggerFactory.getLogger(UserMetadata_Format_V1.class);

    public static int getUserMetadataSize(ByteBuffer userMetadata) {
      return Version_Field_Size_In_Bytes +
          UserMetadata_Size_Field_In_Bytes +
          userMetadata.limit() +
          Crc_Size;
    }

    public static void serializeUserMetadataRecord(ByteBuffer outputBuffer, ByteBuffer userMetadata) {
      int startOffset = outputBuffer.position();
      outputBuffer.putShort(UserMetadata_Version_V1);
      outputBuffer.putInt(userMetadata.limit());
      outputBuffer.put(userMetadata);
      Crc32 crc = new Crc32();
      crc.update(outputBuffer.array(), startOffset, getUserMetadataSize(userMetadata) - Crc_Size);
      outputBuffer.putLong(crc.getValue());
    }

    public static ByteBuffer deserializeUserMetadataRecord(CrcInputStream crcStream)
        throws IOException, MessageFormatException {
      DataInputStream dataStream = new DataInputStream(crcStream);
      int usermetadataSize = dataStream.readInt();
      byte[] userMetadaBuffer = new byte[usermetadataSize];
      dataStream.read(userMetadaBuffer);
      long actualCRC = crcStream.getValue();
      long expectedCRC = dataStream.readLong();
      if (actualCRC != expectedCRC) {
        logger
            .error("corrupt data while parsing user metadata Expected CRC " + expectedCRC + " Actual CRC " + actualCRC);
        throw new MessageFormatException("User metadata is corrupt", MessageFormatErrorCodes.Data_Corrupt);
      }
      return ByteBuffer.wrap(userMetadaBuffer);
    }
  }

  /**
   *  - - - - - - - - - - - - - - - - - - - - - - - -
   * |         |           |            |            |
   * | version |   size    |  content   |     Crc    |
   * |(2 bytes)| (8 bytes) |  (n bytes) |  (8 bytes) |
   * |         |           |            |            |
   *  - - - - - - - - - - - - - - - - - - - - - - - -
   *  version    - The version of the blob record
   *
   *  size       - The size of the blob content
   *
   *  content    - The actual content that represents the blob
   *
   *  crc        - The crc of the blob record
   *
   */
  public static class Blob_Format_V1 {
    public static final int Blob_Size_Field_In_Bytes = 8;
    private static Logger logger = LoggerFactory.getLogger(Blob_Format_V1.class);

    public static long getBlobRecordSize(long blobSize) {
      return Version_Field_Size_In_Bytes +
          Blob_Size_Field_In_Bytes +
          blobSize +
          Crc_Size;
    }

    public static void serializePartialBlobRecord(ByteBuffer outputBuffer, long blobSize) {
      outputBuffer.putShort(Blob_Version_V1);
      outputBuffer.putLong(blobSize);
    }

    public static BlobOutput deserializeBlobRecord(CrcInputStream crcStream)
        throws IOException, MessageFormatException {
      DataInputStream dataStream = new DataInputStream(crcStream);
      long dataSize = dataStream.readLong();
      if (dataSize > Integer.MAX_VALUE) {
        throw new IOException("We only support data of max size == MAX_INT. Error while reading blob from store");
      }
      ByteBufferInputStream output = new ByteBufferInputStream(crcStream, (int) dataSize);
      long crc = crcStream.getValue();
      long streamCrc = dataStream.readLong();
      if (crc != streamCrc) {
        logger.error("corrupt data while parsing blob content expectedcrc {} actualcrc {}", crc, streamCrc);
        throw new MessageFormatException("corrupt data while parsing blob content",
            MessageFormatErrorCodes.Data_Corrupt);
      }
      return new BlobOutput(dataSize, output);
    }
  }
}