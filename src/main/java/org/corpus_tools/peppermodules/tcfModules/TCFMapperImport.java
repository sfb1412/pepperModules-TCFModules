/**
 * Copyright 2009 Humboldt University of Berlin, INRIA.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */
package org.corpus_tools.peppermodules.tcfModules;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.corpus_tools.pepper.common.DOCUMENT_STATUS;
import org.corpus_tools.pepper.impl.PepperMapperImpl;
import org.corpus_tools.pepper.modules.exceptions.PepperModuleDataException;
import org.corpus_tools.salt.SALT_TYPE;
import org.corpus_tools.salt.SaltFactory;
import org.corpus_tools.salt.common.SDocumentGraph;
import org.corpus_tools.salt.common.SPointingRelation;
import org.corpus_tools.salt.common.SSpan;
import org.corpus_tools.salt.common.SStructure;
import org.corpus_tools.salt.common.STextualDS;
import org.corpus_tools.salt.common.SToken;
import org.corpus_tools.salt.core.SAnnotation;
import org.corpus_tools.salt.core.SLayer;
import org.corpus_tools.salt.core.SMetaAnnotation;
import org.corpus_tools.salt.core.SNode;
import org.corpus_tools.salt.core.SRelation;
import org.corpus_tools.salt.graph.Label;
import org.corpus_tools.salt.semantics.SLemmaAnnotation;
import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

public class TCFMapperImport extends PepperMapperImpl {

	/*
	 * TODO: remove unnecessary layers
	 */

	public static final String LEVEL_SENTENCE = "sentence";
	public static final String LAYER_POS = "pos";
	public static final String LAYER_DEPENDENCIES = "dependencies";
	public static final String LEVEL_DEPENDENCY = "dependency";
	public static final String LAYER_TCF_MORPHOLOGY = "morphology";
	public static final String LAYER_CONSTITUENTS = "syntax";
	public static final String LAYER_LEMMA = "lemma";
	public static final String LAYER_REFERENCES = "references";
	public static final String LAYER_NE = "named entities";
	public static final String LAYER_PHONETICS = "phonetics";
	public static final String LAYER_ORTHOGRAPHY = "orthography";
	public static final String LAYER_GEO = "geography";
	public static final String LAYER_LS = "lexical-semantics";
	public static final String LAYER_WORDSENSE = "wordSense";
	public static final String LAYER_SPLITTINGS = "wordSplittings";
	public static final String LAYER_DISCOURSE = "discourseConnectives";
	public static final String LAYER_TEXTSTRUCTURE = "textstructure";
	public static final String LAYER_SENTENCES = "sentences";
	public static final String STYPE_REFERENCE = "reference";
	public static final String STYPE_DEPENDENCY = "dependency";

	private static final String BAD_TOKENIZATION_ERROR_MESSAGE = "Bad tokenization: Full text not matching token text!";

	private static final String REF_SEPERATOR = "%%%";
	public static final String ANNO_NAME_CONSTITUENT = "const";

	private static Logger logger = LoggerFactory.getLogger(TCFMapperImport.class);

	@Override
	public DOCUMENT_STATUS mapSDocument() {
		if (getDocument() == null) {
			setDocument(SaltFactory.createSDocument());
		}
		SDocumentGraph docGraph = SaltFactory.createSDocumentGraph();
		getDocument().setDocumentGraph(docGraph);
		TCFReader reader = new TCFReader();
		this.readXMLResource(reader, getResourceURI());
		return (DOCUMENT_STATUS.COMPLETED);
	}

	private class TCFReader extends DefaultHandler2 implements TCFDictionary {

		/** contains all {@link SNode}s created during the conversion process */
		private EMap<String, SNode> sNodes;
		/**
		 * contains all {@link SLabel}s created during the conversion process
		 */
		private EMap<String, Label> labels;
		/**
		 * contains all {@link SLayer}s created during the conversion process
		 */
		private EMap<String, SLayer> sLayers;
		/** contains the currently used {@link STextualDS} */
		private STextualDS currentSTDS;
		/** This is the pointer used for the tokenization process. */
		private int p;
		/**
		 * contains the path through the xml-document. When a new xml-element
		 * starts it's local name is put on top.
		 */
		private Stack<String> path;
		/** contains the path through the constituent tree. */
		private Stack<String> idPath;
		/** is used to store characters written between tags. */
		private StringBuilder chars;
		/**
		 * points on the currently used / annotated / imported {@link SNode}. It
		 * refers to the id used in sNodes
		 */
		private String currentNodeID;
		/** points on the currently edited annotation. */
		private String currentAnnoID;
		/** points on the currently relevant annotation key. */
		private String currentAnnoKey;
		/** points on the currently relevant sNode. */
		private SNode currentSNode;
		/* internal ids */
		/**
		 * is mainly used for the import of references when ignoreIds is true.
		 * It is also used for some meta data imports.
		 */
		private int id;
		/** is the Id for meta data imports. */
		private int metaId;
		/** is used as id prefix when ignoreIds is false. */
		private static final String REF_PREFIX = "reference-";
		/**
		 * is used as separator between the levels of the meta annotation keys.
		 */
		private static final String CLN = ":";
		/**
		 * is the marking element for span Ids over single tokens to store them
		 * in sNodes without overwriting the {@link SToken}s.
		 */
		private static final String SPAN = "span";
		/* properties */
		/** contains the value of the property SHRINK_TOKEN_ANNOTATIONS. */
		private boolean shrinkTokenAnnotations;
		/** contains the value of the property USE_COMMON_ANNOTATED_ELEMENT. */
		private boolean useCommonAnnotatedElement;
		/* other booleans */
		/**
		 * contains the value of the property IGNORE_FULL_TEXT
		 */
		private boolean ignoreFullText;
		/**
		 */
		private String fullText;
		/**
		 * is set true as soon as the reader finds a duplicated reference Id and
		 * then it ignores the Ids.
		 */
		private boolean ignoreIds;

		private List<SNode> trashList;

		public TCFReader() {
			super();
			sNodes = new BasicEMap<String, SNode>();
			labels = new BasicEMap<String, Label>();
			sLayers = new BasicEMap<String, SLayer>();
			path = new Stack<String>();
			idPath = new Stack<String>();
			chars = new StringBuilder();
			currentNodeID = null;
			currentSNode = null;
			currentAnnoID = null;
			currentAnnoKey = null;
			p = 0;
			shrinkTokenAnnotations = ((TCFImporterProperties) getProperties()).isShrinkTokenAnnotation();
			useCommonAnnotatedElement = ((TCFImporterProperties) getProperties()).isUseCommonAnnotatedElement();
			ignoreFullText = ((TCFImporterProperties) getProperties()).isIgnoreFullText();
			fullText = new String();
			ignoreIds = false;
			id = 0;
			metaId = 0;
			trashList = new ArrayList<SNode>();
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			localName = qName.substring(qName.lastIndexOf(":") + 1);
			path.push(localName);

			if (TAG_TC_CONSTITUENT.equals(localName)) {
				String constID = attributes.getValue(ATT_ID);
				if (!ignoreIds) {
					ignoreIds = (constID == null);
				}
				if (ignoreIds) {
					constID = Integer.toString(id++);
				}
				/*
				 * are we dealing with a potential SToken (sequence) or a
				 * potential SStructure?
				 */
				String tokenIDs = attributes.getValue(ATT_TOKENIDS);
				if (tokenIDs == null) {
					/* SStructure */
					SStructure sStruc = SaltFactory.createSStructure();
					sStruc.createAnnotation(LAYER_CONSTITUENTS, ATT_CAT, attributes.getValue(ATT_CAT));
					store(constID, sStruc);
					sStruc.addLayer(sLayers.get(LAYER_CONSTITUENTS));
					if (idPath.empty()) {
						/* sStruc is root */
						getSDocGraph().addNode(sStruc);
					} else {
						getSDocGraph().addNode(sNodes.get(idPath.peek()), sStruc, SALT_TYPE.SDOMINANCE_RELATION);
					}
					idPath.push(constID);
				} else {
					/* tokens/spans */
					SNode sNode = sNodes.get(tokenIDs);
					if (tokenIDs.contains(" ")) {
						/* span */
						if (sNode == null) {
							String[] seq = tokenIDs.split(" ");
							List<SToken> sTokensForSpan = new ArrayList<SToken>();
							for (int i = 0; i < seq.length; i++) {
								sTokensForSpan.add((SToken) sNodes.get(seq[i]));
							}
							sNode = getSDocGraph().createSpan(sTokensForSpan);
							if (useCommonAnnotatedElement) {
								store(tokenIDs, sNode);
							} // store node, if spans should be reused
						}
						sNode.createAnnotation(LAYER_CONSTITUENTS, ATT_CAT, attributes.getValue(ATT_CAT));
						getSDocGraph().addNode(sNodes.get(idPath.peek()), sNode, SALT_TYPE.SDOMINANCE_RELATION);
						/*
						 * we HAVE TO push also tokens/spans onto the stack to
						 * avoid that at the end of their xml-element the wrong
						 * constituent is popped off the stack
						 */
						idPath.push(tokenIDs);
					} else {
						/* single token */
						if (shrinkTokenAnnotations) {
							sNode = (SToken) sNodes.get(tokenIDs);
						} else {
							sNode = sNodes.get(tokenIDs + SPAN);
							if (sNode == null) {
								sNode = getSDocGraph().createSpan((SToken) sNodes.get(tokenIDs));
								if (useCommonAnnotatedElement) {
									store(tokenIDs + SPAN, sNode);
								} // store node, if spans should be reused
							}
						}
						annotateSNode(sNode, LAYER_CONSTITUENTS, ATT_CAT, attributes.getValue(ATT_CAT), false, false);
						getSDocGraph().addNode(sNodes.get(idPath.peek()), sNode, SALT_TYPE.SDOMINANCE_RELATION);
						/*
						 * we HAVE TO push also tokens onto the stack to avoid
						 * that at the end of their xml-element the wrong
						 * constituent is popped off the stack
						 */
						/*
						 * so the pushed id is actually just a dummy -> we don't
						 * have to check, if the span should be pushed
						 */
						idPath.push(tokenIDs);
					}
				}
			} else if (TAG_TC_DEPPARSING.equals(localName)) {
				currentNodeID = null;
				SLayer depLayer = buildLayer(LAYER_DEPENDENCIES);
				/* TODO the same has to be done in SaltSample, still undone */
				/*
				 * TODO the same has to be done for POS both in
				 * SaltSample(CHECK) and here
				 */
				depLayer.createMetaAnnotation(null, TCFDictionary.ATT_TAGSET, attributes.getValue(TCFDictionary.ATT_TAGSET));
				// depLayer.createMetaAnnotation(null,
				// TCFDictionary.ATT_EMPTYTOKS,
				// attributes.getValue(TCFDictionary.ATT_EMPTYTOKS));
				// depLayer.createMetaAnnotation(null,
				// TCFDictionary.ATT_MULTIGOVS,
				// attributes.getValue(TCFDictionary.ATT_MULTIGOVS));
			} else if (TAG_TC_PARSE.equals(localName)) {
				idPath.clear(); // relevant for constituent parsing
			} else if (TAG_TC_DEPENDENCY.equals(localName)) {
				/*
				 * is there no governing ID, we skip, because we don't use a
				 * root node
				 */
				SDocumentGraph graph = getSDocGraph();
				if (attributes.getValue(ATT_GOVIDS) != null) {
					SPointingRelation depRel = (SPointingRelation) graph.addNode(sNodes.get(attributes.getValue(ATT_GOVIDS)), sNodes.get(attributes.getValue(ATT_DEPIDS)), SALT_TYPE.SPOINTING_RELATION);
					depRel.createAnnotation(LAYER_DEPENDENCIES, ATT_FUNC, attributes.getValue(ATT_FUNC)); // TODO
																											// write
																											// into
																											// documentation,
																											// how
																											// I
																											// use
																											// namespaces
					depRel.addLayer(sLayers.get(LAYER_DEPENDENCIES));
					depRel.setType(STYPE_DEPENDENCY);
				}
			} else if (TAG_TC_SENTENCES.equals(localName)) {
				buildLayer(LAYER_SENTENCES);
			} else if (TAG_MD_METADATA.equals(localName)) {
			} else if (TAG_TC_TEXTCORPUS.equals(localName)) {
				annotateSNode(getDocument(), null, ATT_LANG, attributes.getValue(ATT_LANG), false, true);
				/* work-around to get document name: */
				annotateSNode(getDocument(), null, "document", getDocument().getName(), false, true);
			} else if (TAG_TC_LEMMA.equals(localName)) {
				if (chars.length() > 0) {
					chars.delete(0, chars.length());
				}
				currentNodeID = attributes.getValue(TCFDictionary.ATT_TOKENIDS);
				currentAnnoID = attributes.getValue(TCFDictionary.ATT_ID);
				SNode sNode = getNode(currentNodeID);
				sNode.addLayer(sLayers.get(LAYER_LEMMA));
				currentSNode = sNode;
			} else if (TAG_TC_TEXT.equals(localName)) {
				STextualDS primaryText = SaltFactory.createSTextualDS();
				if (chars.length() > 0) {
					chars.delete(0, chars.length());
				}
				currentSTDS = primaryText;

				getSDocGraph().addNode(primaryText);
				/* reset pointer */
				p = 0;
			} else if (TAG_TC_TOKEN.equals(localName)) {
				currentNodeID = attributes.getValue(TCFDictionary.ATT_ID);
				if (chars.length() > 0) {
					chars.delete(0, chars.length());
				}
			} else if (TAG_TC_LEMMAS.equals(localName)) {
				buildLayer(LAYER_LEMMA);
			} else if (TAG_TC_SENTENCE.equals(localName)) {
				String[] seq = attributes.getValue(ATT_TOKENIDS).split(" ");
				List<SToken> sentenceTokens = new ArrayList<SToken>();
				for (int i = 0; i < seq.length; i++) {
					sentenceTokens.add((SToken) sNodes.get(seq[i]));
				}
				SSpan sentenceSpan = getSDocGraph().createSpan(sentenceTokens);
				String att = attributes.getValue(ATT_ID);
				store(att, sentenceSpan);
				annotateSNode(sentenceSpan, null, TAG_TC_SENTENCE, TAG_TC_SENTENCE, false, false);
				sentenceSpan.addLayer(sLayers.get(LAYER_SENTENCES));
			} else if (TAG_MD_SERVICES.equals(localName)) {
			} else if (TAG_TOOLCHAIN.equals(localName)) {
				metaId = 0;
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_TOOLCHAIN).append(CLN).append(ATT_COMPONENTID).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_TC_TAG.equals(localName)) {
				/*
				 * first check, if we are really in postags and not in
				 * morphology (both use tag "tag"). tag in morphology does not
				 * contain attributes.
				 */
				path.pop();
				if (chars.length() > 0) {
					chars.delete(0, chars.length());
				}
				if (/* attributes.getValue(ATT_TOKENIDS)!=null */TAG_TC_POSTAGS.equals(path.peek())) {
					/* build node for pos annotation */
					currentNodeID = attributes.getValue(ATT_TOKENIDS);
					currentAnnoID = attributes.getValue(ATT_ID);
					SNode sNode = getNode(currentNodeID);
					sNode.addLayer(sLayers.get(LAYER_POS));
					currentSNode = sNode;
				} else if (TAG_TAGS.equals(path.peek())) {
					metaId++;
					chars.delete(0, chars.length());
					annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_TAG).append(metaId).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
				}
			} else if (TAG_TC_POSTAGS.equals(localName)) {
				SLayer posLayer = buildLayer(LAYER_POS);
				if (attributes.getValue(ATT_TAGSET) != null) {
					posLayer.createMetaAnnotation(null, ATT_TAGSET, attributes.getValue(ATT_TAGSET));
				}
			} else if (TAG_RESOURCES.equals(localName)) {
			} else if (TAG_TC_ANALYSIS.equals(localName)) {
				currentNodeID = attributes.getValue(ATT_TOKENIDS);
				SNode sNode = getNode(currentNodeID);
				sNode.addLayer(sLayers.get(LAYER_TCF_MORPHOLOGY));
				currentSNode = sNode;
			} else if (TAG_TC_F.equals(localName)) {
				if (chars.length() > 0) {
					chars.delete(0, chars.length());
				}
				currentAnnoKey = attributes.getValue(ATT_NAME);
			} else if (TAG_TC_SEGMENT.equals(localName)) {
				if (chars.length() > 0) {
					chars.delete(0, chars.length());
				}
				annotateSNode(currentSNode, TAG_TC_SEGMENT, ATT_TYPE, attributes.getValue(ATT_TYPE), false, false);
			} else if (TAG_TC_MORPHOLOGY.equals(localName)) {
				SLayer tcfMorphLayer = SaltFactory.createSLayer();
				tcfMorphLayer.setName(LAYER_TCF_MORPHOLOGY);
				getSDocGraph().addLayer(tcfMorphLayer);
				sLayers.put(LAYER_TCF_MORPHOLOGY, tcfMorphLayer);
			} else if (TAG_TC_REFERENCES.equals(localName)) {
				id = 0; // we might have used it in meta data (actually the
						// start value doesn't matter here)
				ignoreIds = false;
				currentNodeID = null;
				SLayer refLayer = buildLayer(LAYER_REFERENCES);
				if (attributes.getValue(ATT_TYPETAGSET) != null) {
					refLayer.createMetaAnnotation(null, ATT_TYPETAGSET, attributes.getValue(ATT_TYPETAGSET));
				}
				if (attributes.getValue(ATT_RELTAGSET) != null) {
					refLayer.createMetaAnnotation(null, ATT_RELTAGSET, attributes.getValue(ATT_TYPETAGSET));
				}
			} else if (TAG_TC_ENTITY.equals(localName)) {
				path.pop();
				if (path.peek().equals(TAG_TC_NAMEDENTITIES)) {
					currentNodeID = attributes.getValue(ATT_TOKENIDS);
					SNode sNode = getNode(currentNodeID);
					/* annotate */
					annotateSNode(sNode, LAYER_NE, ATT_CLASS, attributes.getValue(ATT_CLASS), false, false);
					/* add to layer */
					sNode.addLayer(sLayers.get(LAYER_NE));
				} else if (path.peek().equals(TAG_TC_REFERENCES)) {
					currentSNode = null;
					idPath.clear();
				}
			} else if (TAG_TC_REFERENCE.equals(localName)) {
				if (!ignoreIds) {
					ignoreIds = (attributes.getValue(ATT_ID).equals(currentNodeID));
				}
				StringBuilder ref = new StringBuilder();
				/* id of reference: */
				currentNodeID = ignoreIds ? REF_PREFIX + id++ : attributes.getValue(ATT_ID);
				currentSNode = getNode(attributes.getValue(ATT_TOKENIDS));
				/* annotate */
				// references can be used in several entities, e.g. "them" with
				// "her" and "him", therefore the annotation could already exist
				annotateSNode(currentSNode, LAYER_REFERENCES, ATT_TYPE, attributes.getValue(ATT_TYPE), false, false);
				store(currentNodeID, currentSNode);// map with reference id --
													// only used with
													// ignoreIds==false
				currentSNode.addLayer(sLayers.get(LAYER_REFERENCES));

				/*
				 * put references on stack to build them later (if it is not the
				 * mentioning of the antecedent)
				 */

				if (attributes.getValue(ATT_REL) != null) {// in webanno files
															// this is false for
															// the last
															// reference
					ref.append(currentSNode.getId()).append(REF_SEPERATOR).append(attributes.getValue(ATT_TARGET)).append(REF_SEPERATOR).append(attributes.getValue(ATT_REL));
					idPath.push(ref.toString());
				} else if (ignoreIds) {
					idPath.push(currentSNode.getId().toString());// target of
																	// all the
																	// others
				}
			} else if (TAG_TC_NAMEDENTITIES.equals(localName)) {
				SLayer namedEntities = buildLayer(LAYER_NE);
				String annoVal = attributes.getValue(ATT_TYPE);
				if (annoVal != null) {
					namedEntities.createMetaAnnotation(null, ATT_TYPE, annoVal);
				}
			} else if (TAG_TC_PHONETICS.equals(localName)) {
				SLayer phoLayer = buildLayer(LAYER_PHONETICS);
				String annoVal = attributes.getValue(ATT_TRANSCRIPTION);
				if (annoVal != null) {
					phoLayer.createMetaAnnotation(null, ATT_TRANSCRIPTION, annoVal);
				}
			} else if (TAG_TC_PRON.equals(localName)) {
				chars.delete(0, chars.length());
				currentNodeID = attributes.getValue(ATT_TOKID);
				currentSNode = shrinkTokenAnnotations ? (SToken) sNodes.get(currentNodeID) : (useCommonAnnotatedElement ? sNodes.get(currentNodeID + SPAN) : getSDocGraph().createSpan((SToken) sNodes.get(currentNodeID)));
				if (currentSNode == null) {// only possible if
											// useCommonAnnotatedElement==true
					currentSNode = getSDocGraph().createSpan((SToken) sNodes.get(currentNodeID));
					store(currentNodeID + SPAN, currentSNode);
				}
				currentSNode.addLayer(sLayers.get(LAYER_PHONETICS));
			} else if (TAG_TC_ORTHOGRAPHY.equals(localName)) {
				buildLayer(LAYER_ORTHOGRAPHY);
			} else if (TAG_TC_CORRECTION.equals(localName)) {
				chars.delete(0, chars.length());
				currentNodeID = attributes.getValue(ATT_TOKENIDS);
				SNode sNode = getNode(currentNodeID);
				SAnnotation correction = sNode.createAnnotation(LAYER_ORTHOGRAPHY, TAG_TC_CORRECTION, null);
				String opVal = attributes.getValue(ATT_OPERATION);
				if (opVal != null) {
					SAnnotation operation = SaltFactory.createSAnnotation();
					operation.setName(ATT_OPERATION);
					operation.setNamespace(LAYER_ORTHOGRAPHY);
					operation.setValue(attributes.getValue(ATT_OPERATION));
					correction.addLabel(operation);
				}
				sNode.addLayer(sLayers.get(LAYER_ORTHOGRAPHY));
				currentSNode = sNode;
			} else if (TAG_TC_GEO.equals(localName)) {// only once allowed
				SLayer geoLayer = buildLayer(LAYER_GEO);
				for (int i = 0; i < attributes.getLength(); i++) {
					geoLayer.createMetaAnnotation(null, attributes.getLocalName(i), attributes.getValue(i));
				}
			} else if (TAG_TC_SRC.equals(localName)) {// only once in <geo>
														// allowed (but
														// obligatory!)
				chars.delete(0, chars.length());
			} else if (TAG_TC_GPOINT.equals(localName)) {// multiple in <geo>
															// allowed (not
															// obligatory)
				SNode sNode = getNode(attributes.getValue(ATT_TOKENIDS));
				/* annotate */
				annotateSNode(sNode, LAYER_GEO, ATT_ALT, attributes.getValue(ATT_ALT), false, false);
				annotateSNode(sNode, LAYER_GEO, ATT_LAT, attributes.getValue(ATT_LAT), false, false);
				annotateSNode(sNode, LAYER_GEO, ATT_LON, attributes.getValue(ATT_LON), false, false);
				annotateSNode(sNode, LAYER_GEO, ATT_CONTINENT, attributes.getValue(ATT_CONTINENT), false, false);
				annotateSNode(sNode, LAYER_GEO, ATT_COUNTRY, attributes.getValue(ATT_COUNTRY), false, false);
				annotateSNode(sNode, LAYER_GEO, ATT_CAPITAL, attributes.getValue(ATT_CAPITAL), false, false);
				sNode.addLayer(sLayers.get(LAYER_GEO));
			} else if (TAG_TC_SYNONYMY.equals(localName) || TAG_TC_ANTONYMY.equals(localName) || TAG_TC_HYPONYMY.equals(localName) || TAG_TC_HYPERONYMY.equals(localName)) {
				if (!sLayers.containsKey(LAYER_LS)) {
					buildLayer(LAYER_LS);
				}
			} else if (TAG_TC_ORTHFORM.equals(localName)) {
				path.pop();
				chars.delete(0, chars.length());
				currentAnnoID = attributes.getValue(ATT_LEMMAREFS);
				SLemmaAnnotation lemma = (SLemmaAnnotation) labels.get(currentAnnoID);
				SAnnotation anno = SaltFactory.createSAnnotation();
				anno.setNamespace(LAYER_LS);
				anno.setName(path.peek());
				lemma.addLabel(anno);
				((SNode) lemma.getContainer()).addLayer(sLayers.get(LAYER_LS));
			} else if (TAG_TC_WSD.equals(localName)) {
				buildLayer(LAYER_WORDSENSE);
				String annoVal = attributes.getValue(ATT_SRC);
				if (annoVal != null) {
					sLayers.get(LAYER_WORDSENSE).createMetaAnnotation(null, ATT_SRC, annoVal);
				}
			} else if (TAG_TC_WS.equals(localName)) {
				SNode sNode = getNode(attributes.getValue(ATT_TOKENIDS));
				annotateSNode(sNode, LAYER_WORDSENSE, ATT_LEXUNITS, attributes.getValue(ATT_LEXUNITS), false, false);
				annotateSNode(sNode, LAYER_WORDSENSE, ATT_COMMENT, attributes.getValue(ATT_COMMENT), false, false);
				sNode.addLayer(sLayers.get(LAYER_WORDSENSE));
			} else if (TAG_TC_WORDSPLITTINGS.equals(localName)) {
				SLayer splitLayer = buildLayer(LAYER_SPLITTINGS);
				if (attributes.getValue(ATT_TYPE) != null) {
					splitLayer.createMetaAnnotation(null, ATT_TYPE, attributes.getValue(ATT_TYPE));
				}
			} else if (TAG_TC_SPLIT.equals(localName)) {
				chars.delete(0, chars.length());
				currentSNode = getNode(attributes.getValue(ATT_TOKID));
				currentSNode.addLayer(sLayers.get(LAYER_SPLITTINGS));
			} else if (TAG_TC_DISCOURSECONNECTIVES.equals(localName)) {
				SLayer discourseLayer = buildLayer(LAYER_DISCOURSE);
				String annoVal = attributes.getValue(ATT_TAGSET);
				if (annoVal != null) {
					discourseLayer.createMetaAnnotation(null, ATT_TAGSET, annoVal);
				}
			} else if (TAG_TC_CONNECTIVE.equals(localName)) {
				SNode sNode = getNode(attributes.getValue(ATT_TOKENIDS));
				annotateSNode(sNode, LAYER_DISCOURSE, ATT_TYPE, attributes.getValue(ATT_TYPE), false, false);
				sNode.addLayer(sLayers.get(LAYER_DISCOURSE));
			} else if (TAG_TC_TEXTSTRUCTURE.equals(localName)) {
				buildLayer(LAYER_TEXTSTRUCTURE);
			} else if (TAG_TC_TEXTSPAN.equals(localName)) {
				if (attributes.getValue(ATT_START) != null && attributes.getValue(ATT_END) != null) {
					SDocumentGraph graph = getSDocGraph();
					SToken startToken = (SToken) sNodes.get(attributes.getValue(ATT_START));
					SToken endToken = (SToken) sNodes.get(attributes.getValue(ATT_END));
					SNode sNode = null;
					if (startToken.equals(endToken)) {
						sNode = shrinkTokenAnnotations ? startToken : graph.createSpan(startToken);
					} else {
						/* we ignore useCommonAnnotatedElement here */
						List<SToken> allTokens = graph.getSortedTokenByText();
						int j = 0;
						while (j < allTokens.size() && !allTokens.get(j).equals(startToken)) {
							j++;
						}
						sNode = graph.createSpan(startToken);
						j++;
						while (j < allTokens.size() && !allTokens.get(j).equals(endToken)) {
							graph.addNode(sNode, allTokens.get(j), SALT_TYPE.SSPANNING_RELATION);
							j++;
						}
						graph.addNode(sNode, allTokens.get(j), SALT_TYPE.SSPANNING_RELATION);
					}
					/* annotate */
					annotateSNode(sNode, LAYER_TEXTSTRUCTURE, ATT_TYPE, attributes.getValue(ATT_TYPE), false, false);
					sNode.addLayer(sLayers.get(LAYER_TEXTSTRUCTURE));
				}
			} else if (TAG_MDCREATOR.equals(localName) || TAG_MDCREATIONDATE.equals(localName) || TAG_MDSELFLINK.equals(localName) || TAG_MDPROFILE.equals(localName) || TAG_MDCOLLECTIONDISPLAYNAME.equals(localName) || TAG_RELATIONTYPE.equals(localName) || TAG_RES1.equals(localName) || TAG_RES2.equals(localName) || TAG_JOURNALFILEREF.equals(localName) || TAG_RESOURCECLASS.equals(localName) || TAG_TIMECOVERAGE.equals(localName) || TAG_LEGALOWNER.equals(localName) || TAG_GENRE.equals(localName) || TAG_LIFECYCLESTATUS.equals(localName) || TAG_STARTYEAR.equals(localName) || TAG_COMPLETIONYEAR.equals(localName) || TAG_PUBLICATIONDATE.equals(localName) || TAG_LASTUPDATE.equals(localName) || TAG_COUNTRYCODING.equals(localName) || TAG_RESOURCEREF.equals(localName) || TAG_PID.equals(localName)) {
				chars.delete(0, chars.length());
			} else if (TAG_RESOURCETYPE.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_RESOURCEPROXY).append(metaId).append(TAG_RESOURCETYPE).append(":").append(ATT_MIMETYPE).toString(), attributes.getValue(ATT_MIMETYPE), false, true);
			} else if (TAG_RESOURCEPROXYLIST.equals(localName)) {
				chars.delete(0, chars.length());
				metaId = 0;
			} else if (TAG_ISPARTOFLIST.equals(localName) || TAG_RESOURCERELATIONLIST.equals(localName) || TAG_JOURNALFILEPROXYLIST.equals(localName)) {
				metaId = 0;
			} else if (TAG_ISPARTOF.equals(localName)) {
				chars.delete(0, chars.length());
				metaId++;
			} else if (TAG_RESOURCERELATION.equals(localName) || TAG_JOURNALFILEPROXY.equals(localName) || TAG_RESOURCEPROXY.equals(localName)) {
				metaId++;
			} else if (TAG_DESCRIPTIONS.equals(localName)) {
				metaId = 0;
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_DESCRIPTIONS).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_DESCRIPTION.equals(localName)) {
				metaId++;
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_DESCRIPTION).append(metaId).append(CLN).append(ATT_TYPE).toString(), attributes.getValue(ATT_TYPE), false, true);
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_DESCRIPTION).append(metaId).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_TC_PARSING.equals(localName)) {
				currentNodeID = null;
				ignoreIds = false;
				SLayer syntaxLayer = buildLayer(LAYER_CONSTITUENTS);
				syntaxLayer.createMetaAnnotation(null, ATT_TAGSET, attributes.getValue(ATT_TAGSET));
			} else if (TAG_GENERALINFO.equals(localName)) {
				metaId = 0;
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(ATT_COMPONENTID).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_RESOURCENAME.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_RESOURCENAME).append(++metaId).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_RESOURCETITLE.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_RESOURCETITLE).append(++metaId).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_CMD.equals(localName)) {
				annotateSNode(getDocument(), null, ATT_CMDVERSION, attributes.getValue(ATT_CMDVERSION), false, true);
			} else if (TAG_VERSION.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_VERSION).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_LOCATION.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LOCATION).append(CLN).append(ATT_COMPONENTID).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_ADDRESS.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_ADDRESS).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_REGION.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_REGION).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_CONTINENTNAME.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_CONTINENTNAME).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_COUNTRYNAME.equals(localName)) {
				chars.delete(0, chars.length());
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_COUNTRY).append(CLN).append(TAG_COUNTRYNAME).append(CLN).append(ATT_LANG).toString(), attributes.getValue(ATT_LANG), false, true);
			} else if (TAG_COUNTRY.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_COUNTRY).append(CLN).append(ATT_COMPONENTID).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_TAGS.equals(localName)) {
				metaId = 0;
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_TAGS).append(CLN).append(ATT_COMPONENTID).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_TOOLINCHAIN.equals(localName)) {
				metaId++;
				id = 0; // we use the reference id as parameter id since it is
						// free for use at this point
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_TOOLCHAIN).append(CLN).append(TAG_TOOLINCHAIN).append(metaId).append(CLN).append(ATT_COMPONENTID).toString(), attributes.getValue(ATT_COMPONENTID), false, true);
			} else if (TAG_PARAMETER.equals(localName)) {
				chars.delete(0, chars.length());
				id++;
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_TOOLCHAIN).append(CLN).append(TAG_TOOLINCHAIN).append(metaId).append(CLN).append(TAG_PARAMETER).append(id).append(CLN).append(ATT_NAME).toString(), attributes.getValue(ATT_NAME), false, true);
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_TOOLCHAIN).append(CLN).append(TAG_TOOLINCHAIN).append(metaId).append(CLN).append(TAG_PARAMETER).append(id).append(CLN).append(ATT_VALUE).toString(), attributes.getValue(ATT_VALUE), false, true);
			}
		}

		@Override
		public void endElement(java.lang.String uri, String localName, String qName) throws SAXException {
			localName = qName.substring(qName.lastIndexOf(":") + 1);
			if (TAG_TC_CONSTITUENT.equals(localName)) {
				idPath.pop();
			} else if (TAG_TC_ENTITY.equals(localName)) {
				if (TAG_TC_REFERENCE.equals(path.peek())) {
					String[] seq = null;
					SNode target = null;
					while (!idPath.empty()) {
						seq = idPath.pop().split(REF_SEPERATOR);
						if (ignoreIds) {
							if (seq.length == 1) {// target/antecedent
								target = getSDocGraph().getNode(seq[0]);
							} else {// ATTENTION target is supposed to be !=null
									// (!!!)
								if (target == null) {
									logger.info("!--------------------------- WARNING: target of reference not set!");
								}
								if (!referenceExists(getSDocGraph().getNode(seq[0]), target)) {
									SPointingRelation ref = (SPointingRelation) getSDocGraph().addNode(getSDocGraph().getNode(seq[0]), target, SALT_TYPE.SPOINTING_RELATION);
									ref.setType(STYPE_REFERENCE);
									ref.createAnnotation(LAYER_REFERENCES, ATT_REL, seq[2]);
									ref.addLayer(sLayers.get(LAYER_REFERENCES));
								}
							}
						} else {
							if (seq.length != 1) {// CHECK isn't that always
													// true?!
								/* relation on antecedent */
								if (!(seq[0].equals(seq[1]))) {
									if (!referenceExists(getSDocGraph().getNode(seq[0]), sNodes.get(seq[1]))) {
										SPointingRelation ref = (SPointingRelation) getSDocGraph().addNode(getSDocGraph().getNode(seq[0]), sNodes.get(seq[1]), SALT_TYPE.SPOINTING_RELATION);
										ref.createAnnotation(LAYER_REFERENCES, ATT_REL, seq[2]);
										ref.setType(STYPE_REFERENCE);
										ref.addLayer(sLayers.get(LAYER_REFERENCES));
									}
								}
							}
						}
					}

				}
			} else if (TAG_TC_TEXT.equals(localName)) {
				String oldtext = currentSTDS.getText();
				currentSTDS.setText(oldtext == null ? chars.toString() : oldtext + chars.toString());
			} else if (TAG_TC_TAG.equals(localName)) {
				/* build annotation – only use in POS */
				// path is popped after opening tag
				if (TAG_TC_POSTAGS.equals(path.peek())) {
					SAnnotation sAnno = SaltFactory.createSPOSAnnotation();
					sAnno.setValue(chars.toString());
					currentSNode.addAnnotation(sAnno);
					labels.put(currentAnnoID, sAnno);
				}
				if (TAG_TAGS.equals(localName)) {
					annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_TAG).append(metaId).toString(), chars.toString(), false, true);
				}
			} else if (TAG_TC_F.equals(localName)) {
				/* build annotation */
				annotateSNode(currentSNode, LAYER_TCF_MORPHOLOGY, currentAnnoKey, chars.toString(), false, false);
			} else if (TAG_TC_LEMMA.equals(localName)) {
				/* build annotation */
				SAnnotation anno = SaltFactory.createSLemmaAnnotation();
				anno.setValue(chars.toString());
				currentSNode.addAnnotation(anno);
				labels.put(currentAnnoID, anno);
			} else if (TAG_TC_TOKEN.equals(localName)) {
				/* build token */
				int old_p = p;
				String primaryData = currentSTDS.getText();
				String tok = chars.toString();
				int lookAhead = (primaryData.substring(p).length() - primaryData.substring(p).trim().length()) + 1;
				while (p < primaryData.length() && (p - old_p) <= lookAhead && !primaryData.substring(p).startsWith(tok)) {
					p++;
				}
				if (p == primaryData.length() || (p - old_p) > lookAhead) {
					logger.warn("WARNING: Skipped token [".concat(tok).concat("] (ID=").concat(currentNodeID).concat("), it could not be found in the base text. This might lead to further errors in processing the document."));
					p = old_p;
					SToken emptyToken = SaltFactory.createSToken();// we'll need
																	// that for
																	// annotations
					getSDocGraph().addNode(emptyToken);
					store(currentNodeID, emptyToken);
					trashList.add(emptyToken);
				} else {
					store(currentNodeID, getSDocGraph().createToken(currentSTDS, p, p + tok.length()));
					p += tok.length();
				}
			} else if (TAG_TC_SEGMENT.equals(localName)) {
				/* build annotation TODO */
				// currentSNode.createAnnotation(TAG_TC_SEGMENT, TAG_TC_SEGMENT,
				// chars.toString());
			} else if (TAG_TC_TOKENS.equals(localName)) {
			} else if (TAG_TC_PRON.equals(localName)) {
				currentSNode.createAnnotation(LAYER_PHONETICS, TAG_TC_PRON, chars.toString());
			} else if (TAG_TC_CORRECTION.equals(localName)) {
				currentSNode.getAnnotation(LAYER_ORTHOGRAPHY + "::" + TAG_TC_CORRECTION).setValue(chars.toString());
			} else if (TAG_TC_SRC.equals(localName)) {
				sLayers.get(LAYER_GEO).createMetaAnnotation(null, TAG_TC_SRC, chars.toString());
			} else if (TAG_TC_ORTHFORM.equals(localName)) {
				labels.get(currentAnnoID).getLabel(LAYER_LS, path.peek()).setValue(chars.toString());
			} else if (TAG_TC_SPLIT.equals(localName)) {
				currentSNode.createAnnotation(LAYER_SPLITTINGS, TAG_TC_SPLIT, chars.toString());
			} else if (TAG_MDCREATOR.equals(localName)) {
				if (chars.length() > 0) {
					SMetaAnnotation meta = getDocument().getMetaAnnotation(TAG_MDCREATOR);
					if (meta != null) {
						meta.setValue(meta.getValue().toString() + "; " + chars.toString());
					} else {
						getDocument().createMetaAnnotation(null, TAG_MDCREATOR, chars.toString());
					}
				}
			} else if (TAG_MDCREATIONDATE.equals(localName)) {
				if (chars.length() > 0) {
					getDocument().createMetaAnnotation(null, TAG_MDCREATIONDATE, chars.toString());
				}
			} else if (TAG_MDSELFLINK.equals(localName)) {
				if (chars.length() > 0) {
					getDocument().createMetaAnnotation(null, TAG_MDSELFLINK, chars.toString());
				}
			} else if (TAG_MDPROFILE.equals(localName)) {
				if (chars.length() > 0) {
					getDocument().createMetaAnnotation(null, TAG_MDPROFILE, chars.toString());
				}
			} else if (TAG_MDCOLLECTIONDISPLAYNAME.equals(localName)) {
				if (chars.length() > 0) {
					getDocument().createMetaAnnotation(null, TAG_MDCOLLECTIONDISPLAYNAME, chars.toString());
				}
			} else if (TAG_TC_TEXTCORPUS.equals(localName)) {
				for (SNode sNode : trashList) {
					getSDocGraph().removeNode(sNode);
				}
			} else if (TAG_RESOURCETYPE.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_RESOURCEPROXY).append(metaId).append(TAG_RESOURCETYPE).toString(), chars.toString(), false, true);
			} else if (TAG_RESOURCEREF.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_RESOURCEPROXY).append(metaId).append(TAG_RESOURCEREF).toString(), chars.toString(), false, true);
			} else if (TAG_JOURNALFILEREF.equals(localName)) {
				annotateSNode(getDocument(), null, TAG_JOURNALFILEPROXY + metaId, chars.toString(), false, true);
			} else if (TAG_RELATIONTYPE.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_RESOURCERELATION).append(metaId).append(":").append(TAG_RELATIONTYPE).toString(), chars.toString(), false, true);
			} else if (TAG_RES1.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_RESOURCERELATION).append(metaId).append(":").append(TAG_RES1).toString(), chars.toString(), false, true);
			} else if (TAG_RES2.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_RESOURCERELATION).append(metaId).append(":").append(TAG_RES2).toString(), chars.toString(), false, true);
			} else if (TAG_ISPARTOF.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_ISPARTOFLIST).append(":").append(TAG_ISPARTOF).append(metaId).toString(), chars.toString(), false, true);
			} else if (TAG_RESOURCENAME.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_RESOURCENAME).append(metaId).toString(), chars.toString(), false, true);
			} else if (TAG_RESOURCETITLE.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_RESOURCETITLE).append(metaId).toString(), chars.toString(), false, true);
			} else if (TAG_RESOURCETITLE.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_RESOURCECLASS).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_VERSION.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_VERSION).toString(), chars.toString(), false, true);
			} else if (TAG_LIFECYCLESTATUS.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LIFECYCLESTATUS).toString(), chars.toString(), false, true);
			} else if (TAG_STARTYEAR.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_STARTYEAR).toString(), chars.toString(), false, true);
			} else if (TAG_COMPLETIONYEAR.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_COMPLETIONYEAR).toString(), chars.toString(), false, true);
			} else if (TAG_PUBLICATIONDATE.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_PUBLICATIONDATE).toString(), chars.toString(), false, true);
			} else if (TAG_LASTUPDATE.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LASTUPDATE).toString(), chars.toString(), false, true);
			} else if (TAG_TIMECOVERAGE.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_TIMECOVERAGE).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_LEGALOWNER.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LEGALOWNER).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_GENRE.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_GENRE).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_ADDRESS.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LOCATION).append(CLN).append(TAG_ADDRESS).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_REGION.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LOCATION).append(CLN).append(TAG_REGION).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_CONTINENTNAME.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LOCATION).append(CLN).append(TAG_CONTINENTNAME).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_COUNTRYNAME.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LOCATION).append(CLN).append(TAG_COUNTRY).append(CLN).append(TAG_COUNTRYNAME).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_COUNTRYCODING.equals(localName)) {
				String qN = (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_LOCATION).append(CLN).append(TAG_COUNTRY).append(CLN).append(TAG_COUNTRYCODING).toString();
				annotateSNode(getDocument(), null, qN, (getDocument().getMetaAnnotation(qN) == null ? chars.toString() : getDocument().getMetaAnnotation(qN).getValue() + "; " + chars.toString()), false, true);
			} else if (TAG_DESCRIPTION.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_GENERALINFO).append(CLN).append(TAG_DESCRIPTION).append(metaId).toString(), chars.toString(), false, true);
			} else if (TAG_PID.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_TOOLCHAIN).append(CLN).append(TAG_TOOLINCHAIN).append(metaId).append(CLN).append(TAG_PID).toString(), chars.toString(), false, true);
			} else if (TAG_PARAMETER.equals(localName)) {
				annotateSNode(getDocument(), null, (new StringBuilder()).append(TAG_WEBSERVICETOOLCHAIN).append(CLN).append(TAG_TOOLCHAIN).append(CLN).append(TAG_TOOLINCHAIN).append(metaId).append(CLN).append(TAG_PARAMETER).append(id).toString(), chars.toString(), false, true);
			}
		}

		/**
		 * This method identifies cosmetic characters used to format the
		 * document which are not relevant for import.
		 * 
		 * @param s
		 *            is the {@link String} to be examined
		 * @return true, if s is empty or only contains formatting characters;
		 *         else false
		 */
		private boolean isPrettyPrint(String s) {
			return s.replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "").isEmpty();
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			StringBuilder txt = new StringBuilder();
			for (int i = start; i < start + length; i++) {
				txt.append(ch[i]);
			}
			boolean add = !isPrettyPrint(txt.toString());
			if (add) {
				chars.append(txt.toString());
			}
		}

		/**
		 * This method returns the correct {@link SNode} from sNodes or builds
		 * it according to the properties SHRINK_TOKEN_ANNOTATIONS and
		 * USE_COMMON_ANNOTATED_ELEMENT all depending on its Id in TCF.
		 * 
		 * @param id
		 *            is the sNodes id
		 * @return
		 */
		private SNode getNode(String id) {
			if (id == null) {
				return null;
			}
			SNode sNode = sNodes.get(id);
			SDocumentGraph graph = getSDocGraph();
			if (id.contains(" ")) {
				if (sNode == null) {
					/* build span */
					String[] seq = id.split(" ");
					sNode = graph.createSpan((SToken) sNodes.get(seq[0]));
					for (int i = 1; i < seq.length; i++) {
						graph.addNode(sNode, (SToken) sNodes.get(seq[i]), SALT_TYPE.SSPANNING_RELATION);
					}
					if (useCommonAnnotatedElement) {
						store(id, sNode);
					}
				}
			} else {// single token
				sNode = shrinkTokenAnnotations ? (SToken) sNode : (useCommonAnnotatedElement ? sNodes.get(id + SPAN) : graph.createSpan((SToken) sNode));
				if (sNode == null) {// only if shrinkTokenAnnotations==false and
									// useCommonAnnotatedElement==true
					/* build span over single token */
					sNode = graph.createSpan((SToken) sNodes.get(id));
					store(id.concat(SPAN), sNode);
				}
			}
			return sNode;
		}

		private void store(String key, SNode sNode) {
			if (sNodes.contains(key)) {
				throw new PepperModuleDataException(TCFMapperImport.this, "Multiple use of id ".concat(key).concat(". IDs are supposed to be unique."));
			}
			sNodes.put(key, sNode);
		}

		/**
		 * *
		 * 
		 * @return the {@link SDocumentGraph}
		 */
		private SDocumentGraph getSDocGraph() {
			return getDocument().getDocumentGraph();
		}

		/**
		 * This method creates and {@link SLayer} and adds it to the
		 * {@link SDocumentGraph}.
		 * 
		 * @param name
		 *            is the {@link SLayer}s SName.
		 * @return the built {@link SLayer}
		 */
		private SLayer buildLayer(String name) {
			SLayer newLayer = SaltFactory.createSLayer();
			newLayer.setName(name);
			sLayers.put(name, newLayer);
			getSDocGraph().addLayer(newLayer);
			return newLayer;
		}

		/**
		 * This method annotates the given {@link SNode}.
		 * 
		 * @param sNode
		 *            to be annotated.
		 * @param namespace
		 * @param name
		 * @param value
		 * @param acceptEmptyOrNullValues
		 * @param isMetaAnnotation
		 *            if true, an {@link SMetaAnnotation} object is created or
		 *            edited instead of an {@link SAnnotation} object.
		 * @return the created {@link Label}
		 */
		private Label annotateSNode(SNode sNode, String namespace, String name, String value, boolean acceptEmptyOrNullValues, boolean isMetaAnnotation) {
			if (sNode == null || name == null) {
				return null;
			}
			if ((value == null || value.isEmpty()) && !acceptEmptyOrNullValues) {
				return null;
			}
			String qName = namespace == null ? name : namespace + "::" + name;
			Label anno = isMetaAnnotation ? sNode.getMetaAnnotation(qName) : sNode.getAnnotation(qName);
			if (anno != null) {
				anno.setValue(value);
			} else {
				anno = isMetaAnnotation ? sNode.createMetaAnnotation(namespace, name, value) : sNode.createAnnotation(namespace, name, value);
			}
			return anno;
		}

		/**
		 * This method checks, if an {@link SPointingRelation} with the sType
		 * "reference" already exists or has to be created.
		 * 
		 * @param sSource
		 * @param sTarget
		 * @return true, if the {@link SPointingRelation} exists.
		 */
		private boolean referenceExists(SNode sSource, SNode sTarget) {
			for (SRelation sRel : sSource.getOutRelations()) {
				if (sRel instanceof SPointingRelation) {
					if ((sRel.getTarget() == sTarget) && (sRel.getType().equals(STYPE_REFERENCE))) {
						return true;
					}
				}
			}
			return false;
		}
	}

}
