package com.ibm.lsd.checker.drivers;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.DatasetFactory;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.AnonId;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.RDFVisitor;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.algebra.Op;
import com.ibm.research.rdf.store.utilities.io.SparqlRdfResultReader;
import com.ibm.research.rdf.store.utilities.io.SparqlSelectResult;
import com.ibm.research.rdf.store.sparql11.model.BlankNodeVariable;
import com.ibm.research.rdf.store.sparql11.model.Constant;
import com.ibm.research.rdf.store.sparql11.model.IRI;
import com.ibm.research.rdf.store.sparql11.model.QueryTripleTerm;
import com.ibm.research.rdf.store.sparql11.model.StringLiteral;
import com.ibm.research.rdf.store.sparql11.model.Variable;
import com.ibm.research.rdf.store.sparql11.semantics.ASTUtils;
import com.ibm.research.rdf.store.sparql11.semantics.BasicUniverse;
import com.ibm.research.rdf.store.sparql11.semantics.BoundedUniverse;
import com.ibm.research.rdf.store.sparql11.semantics.DatasetUniverse;
import com.ibm.research.rdf.store.sparql11.semantics.Drivers;
import com.ibm.research.rdf.store.sparql11.semantics.JenaTranslator;
import com.ibm.research.rdf.store.sparql11.semantics.JenaUtil;
import com.ibm.research.rdf.store.sparql11.semantics.SolutionRelation;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.MapIterator;
import com.ibm.wala.util.collections.Pair;

import kodkod.ast.Formula;
import kodkod.ast.Relation;
import kodkod.instance.TupleSet;

public class LSDDriver {

	@FunctionalInterface
	interface Process {
		void process(Query ast, Op query, BasicUniverse U, JenaTranslator xlator) throws URISyntaxException;
	}

	public static void main(String[] args) throws URISyntaxException, IOException {
		new LSDDriver().mainLoop(args[0], new Tests());
	}

	public void mainLoop(String stem, Process p) throws URISyntaxException, IOException {
		Query ast = JenaUtil.parse(stem + ".rq");
		Op query = JenaUtil.compile(ast);

		System.err.println(query);

		BasicUniverse U = new DatasetUniverse(new URL(stem + "-data.ttl"));
		SparqlSelectResult answer = new SparqlRdfResultReader(stem + "-result.ttl");
		SolutionRelation s = new SolutionRelation(answer, ast.getProjectVars(), Collections.<String,Object>emptyMap());
		s.init(U);
		JenaTranslator xlator = JenaTranslator.make(ast.getProjectVars(), Collections.singleton(query), U, s);
		Pair<Formula, Pair<Formula, Formula>> xlation = xlator.translateSingle(Collections.<String,Object>emptyMap(), false).iterator().next();
		System.err.println(xlation.fst);
		Drivers.check(U, xlation, "solution");

		U = new BoundedUniverse();
		xlator = JenaTranslator.make(ast.getProjectVars(), Collections.singleton(query), U, null);

		p.process(ast, query, U, xlator);
	}

	static class Tests implements Process {
		public void process(Query ast, Op query, BasicUniverse U, JenaTranslator xlator)
				throws URISyntaxException {
			Formula f = Formula.TRUE;
			Formula s1 = null;
			Formula s2 = null;

			Set<Pair<Formula, Pair<Formula, Formula>>> fs = xlator.translateSingle(Collections.<String,Object>emptyMap(), true);

			Set<Relation> prs = HashSetFactory.make();
			formulae: for(Pair<Formula, Pair<Formula, Formula>> p : fs) {
				for(Relation r : ASTUtils.gatherRelations(p.fst)) {
					if (r.name().equals("solution")) {

						Formula thisf = p.fst.and(r.some());
						for(Relation pr : prs) {
							if (r.arity() == pr.arity()) {
								thisf = thisf.and(pr.eq(r).not());
							}
						}

						prs.add(r);

						if (Drivers.check(U, Pair.make(thisf, p.snd), "solution") == null) {
							prs.clear();
							continue formulae;
						}

						Formula nextf = f.and(thisf);
						Formula nexts1 = s1==null? p.snd.fst: p.snd.fst==null? s1: p.snd.fst.and(s1);
						Formula nexts2 = s2==null? p.snd.snd: p.snd.snd==null? s2: p.snd.snd.and(s2);

						TupleSet t = Drivers.check(U, Pair.make(nextf,  Pair.make(nexts1, nexts2)), "quads");
						if (t == null) {
							checkExpanded(ast, query, U, f, s1, s2);
							f = thisf;
							s1 = p.snd.fst;
							s2 = p.snd.snd;
						} else {
							f = nextf;
							s1 = nexts1;
							s2 = nexts2;
						}

						break;
					}
				}
			}

			checkExpanded(ast, query, U, f, s1, s2);
		}
	}
	
	private static void checkExpanded(Query ast, Op query, BasicUniverse U, Formula f, Formula s1, Formula s2)
			throws URISyntaxException {
		SolutionRelation s;
		JenaTranslator xlator;
		Pair<Formula, Pair<Formula, Formula>> xlation;
		TupleSet t = Drivers.check(U, Pair.make(f,  Pair.make(s1, s2)), "quads");
		Dataset dataset = DatasetFactory.createMem();
		Graph G = dataset.asDatasetGraph().getDefaultGraph();
		if (t != null) {
			JenaUtil.addTupleSet(G, t);
		}

		System.out.println("the dataset:");
		RDFDataMgr.write(System.out, dataset, Lang.NQ);

		QueryExecution exec = QueryExecutionFactory.create(ast, dataset);
		SparqlSelectResult rr = new SparqlSelectResult() {
			private final ResultSet results = exec.execSelect();

			@Override
			public Iterator<Row> rows() {
				return new Iterator<Row>() {
					@Override
					public boolean hasNext() {
						return results.hasNext();
					}

					@Override
					public Row next() {
						return new Row() {
							private final QuerySolution row = results.next();

							@Override
							public QueryTripleTerm get(Variable v) {
								RDFNode val = row.get(v.getName());
								if (val == null) return null;
								else return (QueryTripleTerm) val.visitWith(new RDFVisitor() {
									@Override
									public Object visitBlank(Resource r, AnonId id) {
										return new QueryTripleTerm(new BlankNodeVariable(id.getLabelString()));
									}
									@Override
									public Object visitURI(Resource r, String uri) {
										return new QueryTripleTerm(new IRI(uri));									}
									@Override
									public Object visitLiteral(Literal l) {
										StringLiteral s = new StringLiteral(l.getLexicalForm());
										if (l.getLanguage() != null) {
											s.setLanguage(l.getLanguage());
										}
										if (l.getDatatypeURI() != null) {
											s.setType(new IRI(l.getDatatypeURI()));
										}
										return new QueryTripleTerm(new Constant(s));
									}
								});
							}

							@Override
							public Iterator<Variable> variables() {
								return new MapIterator<String,Variable>(row.varNames(), (String name) -> {
									return new Variable(name);
								});
							}
						};
					}
				};
			}

			@Override
			public Iterator<Variable> variables() {
				Iterator<String> vars = results.getResultVars().iterator();
				return new MapIterator<String,Variable>(vars, (String name) -> {
					return new Variable(name);
				});
			}
		};

		U = new DatasetUniverse(dataset);
		s = new SolutionRelation(rr, ast.getProjectVars(), Collections.<String,Object>emptyMap());
		s.init(U);
		xlator = JenaTranslator.make(ast.getProjectVars(), Collections.singleton(query), U, s);
		xlation = xlator.translateSingle(Collections.<String,Object>emptyMap(), false).iterator().next();
		System.err.println(xlation.fst);
		Drivers.check(U, xlation, "solution");
	}
}
