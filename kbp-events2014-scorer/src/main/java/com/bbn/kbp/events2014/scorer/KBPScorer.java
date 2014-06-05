package com.bbn.kbp.events2014.scorer;

import com.bbn.bue.common.StringUtils;
import com.bbn.bue.common.symbols.Symbol;
import com.bbn.kbp.events2014.*;
import com.bbn.kbp.events2014.filters.OnlyMostSpecificTemporal;
import com.bbn.kbp.events2014.io.AnnotationStore;
import com.bbn.kbp.events2014.io.SystemOutputStore;
import com.bbn.kbp.events2014.scorer.observers.*;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.*;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bbn.kbp.events2014.AssessedResponse.IsCorrectUpToInexactJustifications;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Sets.union;

/**
 * A scorer for the KBP event argument-attachment task.
 *
 * @author rgabbard
 *
 */
public final class KBPScorer {
	private static final Logger log = LoggerFactory.getLogger(KBPScorer.class);

    private KBPScorer() {}

    public static KBPScorer create() {
        return new KBPScorer();
    }

    /**
     * Runs the scorer over all documents found in either the system output store
     * or gold annotation store.
     * @throws IOException
     */
    public void run(SystemOutputStore systemOutputStore, AnnotationStore goldAnswerStore,
                    List<KBPScoringObserver<TypeRoleFillerRealis>> corpusObservers,
                    File baseOutputDir) throws IOException
    {
        run(systemOutputStore, goldAnswerStore,
                union(systemOutputStore.docIDs(), goldAnswerStore.docIDs()),
                corpusObservers, baseOutputDir);
    }

    /**
     * Runs the scorer over the specified documents.
     * @throws IOException
     */
    public void run(SystemOutputStore systemOutputStore, AnnotationStore goldAnswerStore,
                    Set<Symbol> documentsToScore,
                    List<KBPScoringObserver<TypeRoleFillerRealis>> corpusObservers,
                    File baseOutputDir) throws IOException
    {
        for (final KBPScoringObserver<TypeRoleFillerRealis> observer : corpusObservers) {
            observer.startCorpus();
        }
        compareOutputToGold(systemOutputStore, goldAnswerStore, documentsToScore,
                corpusObservers, baseOutputDir);
        for (final KBPScoringObserver<TypeRoleFillerRealis> observer : corpusObservers) {
            observer.endCorpus();
        }
    }

	public void compareOutputToGold(final SystemOutputStore systemAnswerStore,
			final AnnotationStore goldAnswerStore,
            final Set<Symbol> documentsToScore,
			final List<KBPScoringObserver<TypeRoleFillerRealis>> corpusObservers,
			final File baseOutputDir)
			throws IOException
	{
		final Map<KBPScoringObserver<TypeRoleFillerRealis>, File> scorerToOutputDir = makeScorerToOutputDir(baseOutputDir, corpusObservers);

        final StringBuilder answerablesList = new StringBuilder();

		for (final Symbol docid : documentsToScore) {
			log.info("Scoring document: {}", docid);

			final AnswerKey key = goldAnswerStore.readOrEmpty(docid);
            // if a correct temporal role is present in the answer key, then
            // all less specific versions of that temporal role are removed
            // from all system output before scoring
            final OnlyMostSpecificTemporal temporalFilter = OnlyMostSpecificTemporal.forAnswerKey(key);

			// Annotators will mark coreference between the entity fillers for arguments.  We load
			// these annotations in order to group e.g. (BasketballGame, Winner, Louisville, Actual)
			// and (BasketballGame, Winner, The Cards, Actual) if the annotator had coreffed
			// "Louisville" and "The Cards"
			final EntityNormalizer entityNormalizer = EntityNormalizer.fromAnnotation(key);

			// we're going to group responses by type, role, filler, realis, where the filler is normalized
			final AnswerKeyAnswerSource<TypeRoleFillerRealis> answerKey = AnswerKeyAnswerSource.forAnswerable(
                    key, TypeRoleFillerRealis.extractFromSystemResponse(entityNormalizer));
			final SystemOutputAnswerSource<TypeRoleFillerRealis> systemOutput =
				SystemOutputAnswerSource.forAnswerable(
                        temporalFilter.apply(systemAnswerStore.readOrEmpty(docid)),
					TypeRoleFillerRealis.extractFromSystemResponse(entityNormalizer));

            final ImmutableMap<KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver,
                    KBPScoringObserver<TypeRoleFillerRealis>> docObserversToCorpusObservers =
                    documentObserversForCorpusObservers(corpusObservers, answerKey, systemOutput);

            final Set<KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver> docObservers =
                    docObserversToCorpusObservers.keySet();

			// notify observers we are starting a new document
			for (final KBPScoringObserver<?>.KBPAnswerSourceObserver observer : docObservers) {
				observer.start();
			}

			final Set<TypeRoleFillerRealis> allAnswerables = ImmutableSet.copyOf(
				concat(systemOutput.answerables(), answerKey.answerables()));

			final Ordering<TypeRoleFillerRealis> order = ByJustificationLocation.create(answerKey, systemOutput);

            final Set<TypeRoleFillerRealis> happyAnswerables = Sets.newHashSet();

			for (final TypeRoleFillerRealis answerable : order.sortedCopy(allAnswerables)) {
				log.info("Scoring equivalence class {}", answerable);

				// notify observers we are starting a new equivalence class
				for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
					observer.startAnswerable(answerable);
				}

				final Set<Response> responses = systemOutput.answers(answerable);
				final Set<AssessedResponse> annotatedResponses = answerKey.answers(answerable);

                if (any(annotatedResponses, IsCorrectUpToInexactJustifications)) {
                    happyAnswerables.add(answerable);
                }

				// let observers see the responses jointly
				for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
					observer.observe(answerable, responses, annotatedResponses);
				}

				if (!responses.isEmpty()) {
					// get safe because responses non-empty
					final Response selectedSystemResponse = systemOutput.systemOutput()
							.selectFromMultipleSystemResponses(responses).get();
					final Optional<AssessedResponse> annotationForArgument =
						AssessedResponse.findAnnotationForArgument(
                                selectedSystemResponse, annotatedResponses);

					if (annotationForArgument.isPresent()) {
						// notify observers we have a selected system response
						// with aligned assessment
						for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
							observer.annotatedSelectedResponse(answerable, selectedSystemResponse,
								annotationForArgument.get(), annotatedResponses);
						}
					} else {
						// notify observers we have a selected system response
						// but not assessment to align to it
						for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
							observer.unannotatedSelectedResponse(answerable, selectedSystemResponse);
						}
					}

					if (annotatedResponses.isEmpty()) {
						// notify observers we have system responses but not annotated responses
						for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
							observer.responsesOnlyNonEmpty(answerable, responses);
						}
					}
				} else if (!annotatedResponses.isEmpty()) {
					// notify observers we have annotated responses but not system responses
					for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
						observer.annotationsOnlyNonEmpty(answerable, annotatedResponses);
					}
				} else {
					throw new RuntimeException("Can't happen: alignment failure.");
				}

				// notify observers we are ending this equivalence class
				for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
					observer.endAnswerable(answerable);
				}
			}

            answerablesList.append(StringUtils.NewlineJoiner.join(order.sortedCopy(happyAnswerables)));
            answerablesList.append("\n");

			// notify observers we are finished with this document
			for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
				observer.end();
			}

			/*for (final Map.Entry<KBPScoringObserver<TypeRoleFillerRealis>, File> corpusOutput : scorerToOutputDir.entrySet()) {
				corpusOutput.getKey().writeCorpusHTML(corpusOutput.getValue());
			}*/

			for (final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver observer : docObservers) {
                File outputDir = new File(scorerToOutputDir.get(docObserversToCorpusObservers.get(observer)),
                        docid.toString());
                outputDir.mkdirs();
                observer.writeDocumentOutput(outputDir);
            }
		}

		log.info("Reports for corpus:");
		for (final KBPScoringObserver<?> observer : corpusObservers) {
			observer.endCorpus();
		}

		for (final Map.Entry<KBPScoringObserver<TypeRoleFillerRealis>, File> corpusOutput : scorerToOutputDir.entrySet()) {
			corpusOutput.getKey().writeCorpusOutput(corpusOutput.getValue());
		}

        Files.asCharSink(new File(baseOutputDir, "happyAnswerables.txt"), Charsets.UTF_8)
                .write(answerablesList.toString());
    }

    private static ImmutableMap<KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver, KBPScoringObserver<TypeRoleFillerRealis>> documentObserversForCorpusObservers(List<KBPScoringObserver<TypeRoleFillerRealis>> corpusObservers, AnswerKeyAnswerSource<TypeRoleFillerRealis> answerKey, SystemOutputAnswerSource<TypeRoleFillerRealis> systemOutput) {
        // for each corpus observer, get a document observer, and maintain a mapping back to the corpus
        // observer it came from. I really wish Java had typedefs
        final ImmutableMap.Builder<KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver, KBPScoringObserver<TypeRoleFillerRealis>>
            docObserverToCorpusObserversBuilder = ImmutableMap.builder();
        for (final KBPScoringObserver<TypeRoleFillerRealis> corpusObserver : corpusObservers) {
            final KBPScoringObserver<TypeRoleFillerRealis>.KBPAnswerSourceObserver docObserver =
                    corpusObserver.answerSourceObserver(systemOutput, answerKey);
            docObserverToCorpusObserversBuilder.put(docObserver, corpusObserver);
        }
        return docObserverToCorpusObserversBuilder.build();
    }

    private static <T> Map<KBPScoringObserver<T>, File> makeScorerToOutputDir(
		final File baseOutputDir, final List<KBPScoringObserver<T>> corpusObservers)
	{
		final ImmutableMap.Builder<KBPScoringObserver<T>, File> ret =
			ImmutableMap.builder();

		for (final KBPScoringObserver<T> observer : corpusObservers) {
			final File outdir = new File(baseOutputDir, observer.name());
			outdir.mkdirs();
			ret.put(observer, outdir);
		}

		return ret.build();
	}


	private static final Symbol PRESENT = Symbol.from("PRESENT");
	private static final Symbol ABSENT = Symbol.from("ABSENT");


	public static final Function<Collection<Response>, Symbol> IsPresent = new Function<Collection<Response>, Symbol> () {
		@Override
		public Symbol apply(final Collection<Response> args) {
			return args.isEmpty()?ABSENT:PRESENT;
		}
	};

	public static final Function<Collection<AssessedResponse>, Symbol> AnyAnswerCorrect = new Function<Collection<AssessedResponse>, Symbol> () {
		@Override
		public Symbol apply(final Collection<AssessedResponse> args) {
			return any(args, AssessedResponse.IsCompletelyCorrect)?PRESENT:ABSENT;
		}
	};

	public static final Function<Collection<AssessedResponse>, Symbol> AnyAnswerSemanticallyCorrect = new Function<Collection<AssessedResponse>, Symbol> () {
		@Override
		public Symbol apply(final Collection<AssessedResponse> args) {
			return any(args, IsCorrectUpToInexactJustifications)?PRESENT:ABSENT;
		}
	};
}