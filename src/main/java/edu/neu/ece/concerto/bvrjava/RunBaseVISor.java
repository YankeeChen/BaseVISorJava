package edu.neu.ece.concerto.bvrjava;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.PropertyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;
import com.vistology.bvr.BaseException;
import com.vistology.bvr.BaseFactory;
import com.vistology.bvr.Configuration;
import com.vistology.bvr.Fact;
import com.vistology.bvr.Observer;
import com.vistology.bvr.compile.DocumentCompiler;
import com.vistology.bvr.compile.DocumentCompiler.CompilationResult;
import com.vistology.bvr.compile.NullFactory;
import com.vistology.bvr.rete.ReteEvent;
import com.vistology.bvr.rete.ReteNet;
import com.vistology.bvr.store.FactBase;
import com.vistology.bvr.store.PlainFactBase;
import com.vistology.bvr.thing.AbbrMap;
import com.vistology.bvr.thing.Thing;
import com.vistology.bvr.thing.ThingFactory;
import com.vistology.bvr.thing.ThingType;
import com.vistology.bvr.util.BaseLogger;

public class RunBaseVISor implements Observer {

	private static final String CONFIG_PATH = "conf" + File.separator;
	private static final String BVR_CONFIG_PATH = CONFIG_PATH + "bvrconfig.xml";
	private static final String MODE_RULE_PATH = "rules" + File.separator;
	private static final String CSV_PATH = "csvfiles" + File.separator;
	private static final String OUTPUT_PATH = "outputfiles" + File.separator;
	private static final String CRO_URI = "http://ece.neu.edu.crf/OBROntology.owl";
	private static final String ECEONTS_URI = "http://ece.neu.edu/ontologies/";
	private static final String ONTS_DIR_NAME = "ontology" + File.separator;
	private static final String INFERRED_BLANK_NS_PREFIX = "http://blank-node/id-";
	private static final Pattern FIND_BLANK_PATTERN = Pattern.compile("\\\"#\\(blank_([0-9]+)\\)\\\"");

	private long blankCounter;
	private DocumentCompiler compiler;
	private ReteNet rete;
	private final BaseLogger log = new BaseLogger(ReteNet.class);
	private final Logger logger = LoggerFactory.getLogger(getClass().getName());

	private static HashMap<String, String[]> MODE_RULE_MAPPER = new HashMap<>();

	static {
		PropertyConfigurator.configure(CONFIG_PATH + "log4j.properties");
		File f = new File(ONTS_DIR_NAME);
		if (f == null || !f.isDirectory() || !f.canRead()) {
			throw new IllegalStateException("Cannot load ontologies from " + ONTS_DIR_NAME);
		}
		// String[] mode1Rules = {MODE_RULE_DIRECTORY + "Mode1_Hardware_Agnostic.bvr",
		// MODE_RULE_DIRECTORY + "Mode1_Hardware_Specific.bvr"};
		MODE_RULE_MAPPER.put("Mode1", new String[] { MODE_RULE_PATH + "Mode1_Hardware_Agnostic.bvr",
				MODE_RULE_PATH + "Mode1_Hardware_Specific.bvr" });
	}

	public RunBaseVISor(String rulesPath, String ontFilePath) {
		blankCounter = 0;
		if (rulesPath == null || rulesPath.isEmpty()) {
			throw new IllegalArgumentException("Cannot run BaseVISor without a rules file.");
		}
		if (ontFilePath == null || ontFilePath.isEmpty()) {
			throw new IllegalArgumentException("Cannot run BaseVISor rules without an ontology.");
		}

		File f = new File(rulesPath);
		File o = new File(ontFilePath);

		if (f == null || !f.canRead()) {
			throw new IllegalArgumentException("Cannot read the rules file from " + rulesPath);
		}

		if (o == null || !o.canRead()) {
			throw new IllegalArgumentException("Cannot read the ontology file from " + ontFilePath);
		}
		initialize(rulesPath, ontFilePath);
	}

	private void initialize(String rulesPath, String ontFilePath) {
		logger.info("Initializing BaseVISor..");
		try {
			Configuration config = new Configuration(BVR_CONFIG_PATH);
			compiler = new DocumentCompiler(config, getURIMapping(ontFilePath), this, 0, log);
			compiler.register(new ProceduralAttachments(compiler.getThingFactory()));
			logger.debug("Compiling the following rules file:{}", rulesPath);
			CompilationResult compResult = compiler.compile(rulesPath);
			BaseFactory baseFactory = compResult.getBaseFactory();
			if (baseFactory == null || baseFactory instanceof NullFactory)
				throw new RuntimeException("Failed to process the rules document.");
			rete = compResult.getRete() == null ? baseFactory.constructReteNet() : compResult.getRete();
		} catch (Exception e) {
			logger.error("There was an error while initializing BaseVISor.", e);
			e.printStackTrace();
		}
		logger.info("There are {} asserted facts in the knowledge base.", rete.getFactCount());
		logger.info("Initialization complete.");
	}

	private HashMap<String, String> getURIMapping(String ontFilePath) {
		HashMap<String, String> uriMap = new HashMap<>();
		uriMap.put(ECEONTS_URI + "ConcertOlogy_ABox.owl", getOntURI(ontFilePath));
		uriMap.put(ECEONTS_URI + "ConcertOlogy.owl", getOntURI(ontFilePath));
		uriMap.put(CRO_URI, getOntURI(ontFilePath));
		logger.debug("URI Mapping:\n{}", uriMap);
		return uriMap;
	}

	private static String getOntURI(String ontFilePath) {
		File ontFile = new File(ontFilePath);
		return ontFile.toURI().toString();
	}

	public String toRDF(boolean prettyRDF, boolean unblank, boolean filtUninterestingFacts) {
		StringBuilder sb = new StringBuilder();
		sb.append("<rdf:RDF\n");
		AbbrMap abbreviations = rete.getAbbreviations();
		abbreviations.displayGlobalDeclarations(sb, "  ");
		sb.append(">");
		if (prettyRDF) {
			if (filtUninterestingFacts)
				sb.append(filterRete(rete).toRDF(abbreviations, rete.getThingFactory()));
			else
				sb.append(rete.getFactBase().toRDF(abbreviations, rete.getThingFactory()));
		} else {
			if (filtUninterestingFacts)
				sb.append(filterRete(rete).toRDFFast(abbreviations, rete.getThingFactory()));
			else
				sb.append(rete.getFactBase().toRDFFast(abbreviations, rete.getThingFactory()));
		}
		sb.append("</rdf:RDF>\n");

		String rdf = sb.toString();
		return unblank ? unblank(rdf) : rdf;
	}

	/**
	 * Replace all #(blank_no) with resources so that the produced document is
	 * properly handled by Jena.
	 * 
	 * @param rdf
	 *            RDF/XML document
	 * @return RDF document without the blank nodes, which can be inserted into the
	 *         triple store
	 */
	private String unblank(String rdf) {
		logger.info("Removing blanks from the RDF triples.");
		if (StringUtils.isBlank(rdf)) {
			return rdf;
		}
		Matcher m = FIND_BLANK_PATTERN.matcher(rdf);
		Set<String> blanks = new HashSet<String>();
		if (m.find()) {
			blanks.add(m.group(0));
			while (m.find()) {
				blanks.add(m.group(0));
			}
			logger.trace("Blanks in the document for the triple store: {}", blanks);
			for (String blank : blanks) {
				String replacement = "\"" + INFERRED_BLANK_NS_PREFIX + (blankCounter++) + "\"";
				logger.trace("Replacing {} with {}", blank, replacement);
				rdf = StringUtils.replace(rdf, blank, replacement);
			}
		} else {
			logger.trace("No blanks in the document for the triple store.");
		}

		return rdf;
	}

	/**
	 * TODO You can remove other uninteresting facts here.
	 * 
	 * @param net
	 *            ReteNet after inference
	 * @return The same facts as in Rete, but skip internal triples
	 */
	private FactBase filterRete(ReteNet net) {
		FactBase facts = new PlainFactBase();
		for (Fact f : net.getFacts()) {
			Thing s = f.getSubject();
			Thing p = f.getPredicate();
			Thing o = f.getObject();
			String ss = s.toString();
			String ps = p.toString();
			String os = o.toString();

			if (ps.startsWith("#_") || ps.contains("_matchedClasses") || ps.contains("_sublist")) {
				continue;
			}

			if (s.getType().equals(ThingType.BLANK_ASSET)) {
				continue;
			}

			if (p.getType().equals(ThingType.BLANK_ASSET)) {
				continue;
			}

			if (o.getType().equals(ThingType.BLANK_ASSET)) {
				continue;
			}

			if (ps.contains("owl:imports")) {
				continue;
			}

			if (ss.equalsIgnoreCase("owl:Nothing")) {
				continue;
			}

			if (ss.equalsIgnoreCase("owl:Thing")) {
				continue;
			}

			if (os.equalsIgnoreCase("owl:Thing")) {
				continue;
			}

			if (ps.equalsIgnoreCase("rdf:type") && os.equalsIgnoreCase("owl:Thing")) {
				continue;
			}

			if (ps.equalsIgnoreCase("rdf:type") && os.equalsIgnoreCase("owl:Class")) {
				continue;
			}

			if (ps.equalsIgnoreCase("rdfs:subClassOf") && os.equalsIgnoreCase("owl:Thing")) {
				continue;
			}

			if (s.equals(o)) {

				if (ps.equalsIgnoreCase("owl:sameAs")) {
					continue;
				}

				if (ps.equalsIgnoreCase("rdfs:subClassOf")) {
					continue;
				}

				if (ps.equalsIgnoreCase("owl:equivalentClass")) {
					continue;
				}

				if (ps.equalsIgnoreCase("owl:equivalentProperty")) {
					continue;
				}

				if (ps.equalsIgnoreCase("rdfs:subPropertyOf")) {
					continue;
				}
			}

			if (s.getType().equals(ThingType.PLAIN) || s.getType().isNumeric() || s.getType().isXsdString()
					|| s.getType().isXsdDateTime()) {
				continue;
			}

			facts.insert(f);
		}

		logger.info("Number of facts after filtering: {}", facts.size());
		return facts;
	}

	public void observe(ReteEvent event) {
	}

	public void run(String outputFilePath, boolean prettyRDF, boolean unblank, boolean filterUninteresting) {
		logger.info("Running inference...");
		try {
			rete.run();
		} catch (BaseException e) {
			logger.error("There was an error while running the inference engine.", e);
			return;
		}
		logger.info("Number of facts after running inference: {}", rete.getFactCount());

		logger.info("Exporting Rete to RDF...");
		String rdfDump = toRDF(prettyRDF, unblank, filterUninteresting);

		logger.info("Saving RDF to a file...");
		try {
			FileUtils.writeStringToFile(new File(outputFilePath), rdfDump, StandardCharsets.UTF_8);
		} catch (IOException e) {
			logger.error("There was an error while dumping into file.", e);
		}

		logger.info("Final RDF facts are in {}", outputFilePath);
		logger.info("Done.\n");
	}

	public Logger getBaseVISorLogger() {
		return logger;
	}

	public void insertFacts(String csvFileName, String importFilePath, String outputFilePath) {
		if (csvFileName == null || csvFileName.isEmpty()) {
			throw new IllegalArgumentException("The CSV file name is null or empty.");
		}
		String csvPath = CSV_PATH + csvFileName;
		File f = new File(csvPath);

		if (f == null || !f.canRead()) {
			throw new IllegalArgumentException("Cannot read the csv file from " + csvPath);
		}

		ThingFactory thingFactory = rete.getThingFactory();

		CSVReader reader = null;
		String[] line;
		int count = 0;

		try {
			reader = new CSVReader(new FileReader(csvPath));
			while ((line = reader.readNext()) != null) {
				if (count++ == 0)
					continue;
				rete.assertFact(new Fact(thingFactory.constructAsset(ECEONTS_URI + "ConcertOlogy_ABox.owl#" + line[2]),
						thingFactory.constructAsset(ECEONTS_URI + "ConcertOlogy.owl#" + "isRealizedOn"),
						thingFactory.constructAsset(ECEONTS_URI + "ConcertOlogy_ABox.owl#" + line[0])));
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		String modeName = "Mode1";
		String csvFileName = "Mode1_HW.csv";
		try {
			if (!args[0].equalsIgnoreCase("-modeName") || !args[2].equalsIgnoreCase("-csvFileName"))
				throw new Exception();
			modeName = args[1];
			csvFileName = args[3];
		} catch (Exception e) {
			System.err.println("Usage: BaseVISorJava\n" + "-modeName The name of the requested mode; Mode1 by default\n"
					+ "-csvFileName The name of the csv file generated by Solver; Mode1_HW.csv by default\n");
			System.exit(0);
		}
		String Mode_Generic_Without_Scheduling_PATH = OUTPUT_PATH + modeName + "_Generic_Without_Scheduling.rdf";
		String Mode_Generic_With_Scheduling_PATH = OUTPUT_PATH + modeName + "_Generic_With_Scheduling.rdf";
		String Mode_Concrete_PATH = OUTPUT_PATH + modeName + "_Concrete.rdf";
		long timeStart = System.currentTimeMillis();

		RunBaseVISor rbv = new RunBaseVISor(MODE_RULE_MAPPER.get(modeName)[0], ONTS_DIR_NAME + "ConcertOlogy.owl");
		rbv.run(Mode_Generic_Without_Scheduling_PATH, true, true, true);

		rbv.insertFacts(csvFileName, Mode_Generic_Without_Scheduling_PATH, Mode_Generic_With_Scheduling_PATH);
		rbv.run(Mode_Generic_With_Scheduling_PATH, true, true, true);
		
		rbv = new RunBaseVISor(MODE_RULE_MAPPER.get(modeName)[1], Mode_Generic_With_Scheduling_PATH); 
		rbv.run(Mode_Concrete_PATH, true, true, true);
		 
		long totalTime = System.currentTimeMillis() - timeStart;
		rbv.getBaseVISorLogger().info("Total running time is {} ms.", totalTime);
	}
}
