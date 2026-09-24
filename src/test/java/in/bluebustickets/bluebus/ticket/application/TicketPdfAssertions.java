package in.bluebustickets.bluebus.ticket.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.lowagie.text.pdf.PRStream;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

public final class TicketPdfAssertions {

    private TicketPdfAssertions() {
    }

    public static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    public static String decodeQr(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfDictionary page = reader.getPageN(1);
            PdfDictionary resources = page.getAsDict(PdfName.RESOURCES);
            PdfDictionary xobjects = resources.getAsDict(PdfName.XOBJECT);
            if (xobjects == null) {
                throw new AssertionError("PDF did not contain a QR image.");
            }
            for (PdfName name : xobjects.getKeys()) {
                PdfObject object = xobjects.getDirectObject(name);
                if (object instanceof PRStream stream && PdfName.IMAGE.equals(stream.getAsName(PdfName.SUBTYPE))) {
                    BufferedImage image = streamToImage(stream);
                    if (image == null) {
                        continue;
                    }
                    Result result = new MultiFormatReader().decode(
                            new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))));
                    return result.getText();
                }
            }
        } finally {
            reader.close();
        }
        throw new AssertionError("PDF did not contain a decodable QR image.");
    }

    private static BufferedImage streamToImage(PRStream stream) throws Exception {
        byte[] bytes = PdfReader.getStreamBytes(stream);
        BufferedImage encoded = ImageIO.read(new ByteArrayInputStream(bytes));
        if (encoded != null) {
            return encoded;
        }
        int width = stream.getAsNumber(PdfName.WIDTH).intValue();
        int height = stream.getAsNumber(PdfName.HEIGHT).intValue();
        if (bytes.length < width * height * 3) {
            return null;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = bytes[index++] & 0xff;
                int green = bytes[index++] & 0xff;
                int blue = bytes[index++] & 0xff;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }
}
