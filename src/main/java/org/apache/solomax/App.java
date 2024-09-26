package org.apache.solomax;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.openmeetings.db.dao.label.LabelDao.getLabelFileName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.cxf.ext.logging.LoggingInInterceptor;
import org.apache.cxf.ext.logging.LoggingOutInterceptor;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.ContentDisposition;
import org.apache.cxf.jaxrs.ext.multipart.MultipartBody;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.openmeetings.db.entity.label.StringLabel;
import org.apache.openmeetings.util.OmFileHelper;
import org.apache.openmeetings.util.XmlExport;
import org.apache.wicket.util.string.Strings;
import org.dom4j.Document;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

/**
 * Hello world!
 *
 */
public class App {
	private static Logger log = LoggerFactory.getLogger(App.class);
	private static final String ENTRY_ELEMENT = "entry"; //FIXME TODO make it public in LabelDao
	private static final String KEY_ATTR = "key"; //FIXME TODO make it public in LabelDao
	private static final String API_BASE = "https://api.poeditor.com/v2/";
	private static final String PROJECT_ID = "333773";
	private static final String MODE_PUT = "put";
	private static final String MODE_LIST = "list";
	private static final String LNG_EN = "en";
	private static final String LNG_PO_ZH_CN = "zh-Hans";
	private static final String LNG_ZH_CN = "zh-CN";
	private static final String LNG_PO_ZH_TW = "zh-Hant";
	private static final String LNG_ZH_TW = "zh-TW";
	private static final Map<String, String> LNG_PO_TO_OM = Map.of(
			LNG_PO_ZH_CN, LNG_ZH_CN
			, LNG_PO_ZH_TW, LNG_ZH_TW);
	private static final Map<String, String> LNG_OM_TO_PO = Map.of(
			LNG_ZH_CN, LNG_PO_ZH_CN
			, LNG_ZH_TW, LNG_PO_ZH_TW);
	public static final long TIMEOUT = 50 * 1000; // connection timeout 50sec
	public static final long REQ_TIMEOUT = 30 * 1000; // 30sec

	private static Attachment newAttachment(String name, String val) {
		return new Attachment(
				name
				, new ByteArrayInputStream(val.getBytes(UTF_8))
				, new ContentDisposition("form-data; name=\"" + name + "\";"));
	}

	private static JSONObject post(String path, Function<WebClient, Response> func) {
		WebClient c = WebClient.create(API_BASE).accept(MediaType.APPLICATION_JSON);
		HTTPClientPolicy p = WebClient.getConfig(c).getHttpConduit().getClient();
		p.setConnectionTimeout(TIMEOUT);
		p.setReceiveTimeout(TIMEOUT);

		ClientConfiguration config = WebClient.getConfig(c);
		config.getInInterceptors().add(new LoggingInInterceptor());
		config.getOutInterceptors().add(new LoggingOutInterceptor());

		Response resp = func.apply(c.path(path));

		String jsonStr = resp.readEntity(String.class);
		if (200 != resp.getStatus()) {
			log.error("Fail to call {} -> {}", path, jsonStr);
			System.exit(1);
		}
		JSONObject result = new JSONObject(jsonStr);
		JSONObject response = result.getJSONObject("response");
		if ("fail".equals(response.getString("status"))) {
			log.error("call to {} was unsuccessful {}", path, jsonStr);
			System.exit(1);
		}
		return result.getJSONObject("result");
	}

	private static JSONObject post(String path, String token, MultipartBody body) {
		return post(path, c -> {
			c.type(MediaType.MULTIPART_FORM_DATA);
			body.getAllAttachments().add(newAttachment("api_token", token));
			body.getAllAttachments().add(newAttachment("id", PROJECT_ID));
			return c.post(body);
		});
	}

	private static JSONObject post(String path, String token, Form params) {
		return post(path, c -> c.post(params.param("id", PROJECT_ID).param("api_token", token)));
	}

	private static Properties load(Path folder, String fileName) {
		Properties props = new Properties();
		try (FileInputStream is = new FileInputStream(folder.resolve(fileName).toFile())) {
			props.loadFromXML(is);
		} catch (Exception e) {
			log.error("Unexpected error while reading properties", e);
		}
		return props;
	}

	private static Properties load(String token, String code) throws Exception {
		JSONObject translation = post("projects/export", token, new Form().param("language", code).param("type", "properties"));
		URL url = new URL(translation.getString("url"));
		try (InputStream is = url.openStream(); Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
			Properties props = new Properties();
			props.load(r);
			return props;
		} catch (Exception e) {
			log.error("Unexpected error while reading properties", e);
			System.exit(1);
		}
		return null;
	}

	private static void save(Path path, String inCode, Properties lang, Properties eng) throws Exception {
		List<StringLabel> labels = new ArrayList<>();
		eng.forEach((inKey, value) -> {
			String key = (String) inKey;
			String oVal = lang.getProperty(key);
			String val = Strings.isEmpty(oVal) ? (String) value : oVal;
			labels.add(new StringLabel(key, val.replace("''", "'")));
		});
		String code = LNG_PO_TO_OM.getOrDefault(inCode, inCode);
		Locale l = Locale.forLanguageTag(code);
		Document d = XmlExport.createDocument();
		Element r = XmlExport.createRoot(d);
		Collections.sort(labels, (o1, o2) -> {
			int val;
			try {
				int i1 = Integer.parseInt(o1.getKey()), i2 = Integer.parseInt(o2.getKey());
				val = i1 - i2;
			} catch (Exception e) {
				val = o1.getKey().compareTo(o2.getKey());
			}
			return val;
		});
		for (StringLabel sl : labels) {
			r.addElement(ENTRY_ELEMENT).addAttribute(KEY_ATTR, sl.getKey()).addCDATA(sl.getValue());
		}
		final String fName = getLabelFileName(l);
		log.debug("Got lang '{}'->'{}', locale: {}, resulting file: {}", inCode, code, l, fName);
		XmlExport.toXml(path.resolve(fName).toFile(), d);
	}

	private static void send(String token, String fileName, String engName, Properties eng, Properties props) {
		try (ByteArrayOutputStream os = new ByteArrayOutputStream(100_000);
				Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);) {
			if (!engName.equals(fileName)) {
				for (Iterator<Map.Entry<Object, Object>> iter = props.entrySet().iterator(); iter.hasNext();) {
					Map.Entry<Object, Object> e = iter.next();
					if (eng.getProperty((String) e.getKey()).equals(e.getValue())) {
						iter.remove();
					}
				}
			}
			props.store(writer, "Apache OpenMeetings language file");
			String fname = OmFileHelper.getFileName(OmFileHelper.getFileName(fileName)); // *.properties.xml
			String langCode = LNG_EN;
			if (fname.indexOf("_") > 0) {
				langCode = fname.substring(fname.indexOf("_") + 1).replaceAll("_", "-");
				langCode = LNG_OM_TO_PO.getOrDefault(langCode, langCode);
			}

			List<Attachment> atts = new ArrayList<>();
			atts.add(new Attachment("file", new ByteArrayInputStream(os.toByteArray()),
					new ContentDisposition("form-data; name=\"file\";filename=\"app.properties\"")));
			atts.add(newAttachment("updating", "terms_translations"));
			atts.add(newAttachment("language", langCode));
			atts.add(newAttachment("overwrite", "1"));
			atts.add(newAttachment("sync_terms", engName.equals(fileName) ? "1" : "0"));
			JSONObject result = post("projects/upload", token, new MultipartBody(atts));
			log.error("{} -> {}", langCode, result);
			sleep();
		} catch (Exception e) {
			log.error("Unexpected exception while sending", e);
			System.exit(1);
		}
	}

	private static void sleep() throws InterruptedException {
		log.info("... going to sleep for {} seconds", REQ_TIMEOUT / 1000);
		Thread.sleep(REQ_TIMEOUT);
		log.info("Done!");
	}

	public static void main(String[] args) throws Exception {
		log.info("Usage: .token om-src-root [mode]");
		log.info("mode can be '-empty-', 'put', 'list'");
		log.info("Specific language can be specified after 'put', for ex. 'ar'{}{}", System.lineSeparator(), System.lineSeparator());
		if (args.length < 2) {
			return;
		}
		String token = Files.readString(Path.of(args[0])).trim();

		Path srcRoot = Path.of(args[1], "openmeetings-web/src/main/java/org/apache/openmeetings/web/app");
		if (args.length > 2 && MODE_LIST.equalsIgnoreCase(args[2])) {
			JSONObject result = post("languages/list", token, new Form());
			JSONArray langs = result.getJSONArray("languages");
			for (int i = 0; i < langs.length(); ++i) {
				JSONObject o = langs.getJSONObject(i);
				log.info("\t{} -> {}", o.getString("name"), o.getString("code"));
			}
		} else if (args.length > 2 && MODE_PUT.equalsIgnoreCase(args[2])) {
			String engName = getLabelFileName(Locale.ENGLISH);
			Properties eng = load(srcRoot, engName);
			if (args.length > 3) {
				Files.list(srcRoot).filter(path -> path.toString().endsWith("_" + args[3] + ".properties.xml")).findFirst().ifPresentOrElse(path -> {
					String fileName = path.getFileName().toString();
					Properties props = load(srcRoot, fileName);
					send(token, fileName, engName, eng, props);
				}, () -> log.error("Language file for '" + args[3] + "' locale not found"));
			} else {
				send(token, engName, engName, eng, eng);
				Files.list(srcRoot).filter(path -> path.toString().endsWith(".xml")).forEach(path -> {
					String fileName = path.getFileName().toString();
					Properties props = load(srcRoot, fileName);
					if (!engName.equals(fileName)) {
						send(token, fileName, engName, eng, props);
					}
				});
			}
		} else {
			Properties langEng = load(token, LNG_EN);
			JSONObject json = post("languages/list", token, new Form());
			JSONArray languages = json.getJSONArray("languages");
			for (int i = 0; i < languages.length(); ++i) {
				JSONObject langJson = languages.getJSONObject(i);
				String code = langJson.getString("code");
				Properties lang = LNG_EN.equals(code) ? langEng : load(token, code);
				save(srcRoot, code, lang, langEng);
				log.warn("Got {} [{}], {}%{}{}", langJson.getString("name"), code, langJson.getString("percentage"), System.lineSeparator(), System.lineSeparator());
				sleep();
			}
		}
	}
}
