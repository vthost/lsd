package lsd;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.engine.http.QueryEngineHTTP;
import org.apache.jena.sparql.expr.E_Exists;
import org.apache.jena.sparql.expr.E_LogicalNot;
import org.apache.jena.sparql.expr.E_NotExists;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.Element1;
import org.apache.jena.sparql.syntax.ElementExists;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementMinus;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementNotExists;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.ElementUnion;


public class LogQueryDataExtractor {
	
	private int defaultDataLimit = 10;
	
//	TODO not sure if Service, Subquery, and Graph are treated correctly everywhere
//	
//	jena sparql syntax Element that can be ignored:
//	ElementAssign, ElementBind: neither jena sparql syntax Elements nor bgps in expressions
//	(except in (not) exists, but that may only occur in filters - according to the spec)
//	ElementData: (looks as if it) represents rdf data, bindings of variables to nodes
//	maybe also the sparql values clause
	private List<Element> extractBGPs(Element e) {	

		if(e instanceof Element1) {
			
			return extractBGPs(((Element1) e).getElement());
			
		} else if(e instanceof ElementFilter && //one of E_Exists, E_NotExists
				((ElementFilter) e).getExpr() instanceof ExprFunctionOp) { 

			return extractBGPs(((ExprFunctionOp) ((ElementFilter) e).getExpr()).getElement());
		
		} else if(e instanceof ElementGroup) {
			
			return extractBGPs(((ElementGroup) e).getElements());
					
		} else if(e instanceof ElementMinus) {
			
			return extractBGPs(((ElementMinus) e).getMinusElement());
		
		} else if(e instanceof ElementNamedGraph) {
			
			return extractBGPs(((ElementNamedGraph) e).getElement());
			
		} else if(e instanceof ElementOptional) {
			
			return extractBGPs(((ElementOptional) e).getOptionalElement());
			
		} else if(e instanceof ElementPathBlock) {
			
			List<Element> l = new ArrayList<Element>();		
			l.add((Element) e);
			return l;
			
		} else if(e instanceof ElementService) {
			
			return extractBGPs(((ElementService) e).getElement());
					
		} else if(e instanceof ElementSubQuery) {
				
			return extractBGPs(((ElementSubQuery) e).getQuery().getQueryPattern());
						
		} else if(e instanceof ElementTriplesBlock) {
			
			List<Element> l = new ArrayList<Element>();		
			l.add((Element) e);
			return l;
			
		} else if(e instanceof ElementUnion) {
			
			return extractBGPs(((ElementUnion) e).getElements());			
		} 
		
		return new ArrayList<Element>();		
	}

	private List<Element> extractBGPs(List<Element> l) {		
		
		List<Element> l2 = new ArrayList<Element>();		
		for (Element e : l) l2.addAll(extractBGPs(e));
	
		return l2;
	}
	

	//	http://jvalentino.blogspot.de/2007/02/shortcut-to-calculating-power-set-using.html
	private <T> List<List<T>> powerset(Collection<T> list) {
		  List<List<T>> ps = new ArrayList<List<T>>();
		  ps.add(new ArrayList<T>());   // add the empty set
		 
		  // for every item in the original list
		  for (T item : list) {
		    List<List<T>> newPs = new ArrayList<List<T>>();
		 
		    for (List<T> subset : ps) {
		      // copy all of the current powerset's subsets
		      newPs.add(subset);
		 
		      // plus the subsets appended with the current item
		      List<T> newSubset = new ArrayList<T>(subset);
		      newSubset.add(item);
		      newPs.add(newSubset);
		    }
		 
		    // powerset is now powerset of list.subList(0, list.indexOf(item)+1)
		    ps = newPs;
		  }
		  return ps;
	}
	
	private <T> List<List<T>> oneElementPowerset(List<List<T>> list) {
		
		if(list.size() == 1) {
			return list.get(0).stream().map(e -> { 
				List<T>  l = new ArrayList<T>();
				l.add(e);
				return l;
			}).collect(Collectors.toList());	
		}
		
		List<T> first = list.get(0);
		list.remove(0);		
		list = oneElementPowerset(list); 

		List<List<T>> newlist = new ArrayList<List<T>>();
		
		for (T t : first) {
			for (List<T> l : list) {
				List<T> l2 = new ArrayList<T>();
				l2.addAll(l);
				l2.add(t);
				newlist.add(l2);
			}
		}

		return newlist;
	}
	
//	TODO discuss if solutions are ok like this
//	
//	we ignore ElementDataset since it is unused by the parser (according to the JavaDoc)
	private List<Element> getInVariability(Element e, String qs) {	
		
//		not sure where this class, Element(Not)Exists, is actually used. 
//		in filter this is not possible given the type?!
//		TODO add variability?
		if(e instanceof ElementExists) {
			
			List<Element> els = getInVariability(
						(((ElementExists) e).getElement()),qs);
			els.stream().map(e1 -> new ElementExists(e1));
			return els;
		
		} else if(e instanceof ElementNotExists) {
			
			List<Element> els = getInVariability(
						(((ElementExists) e).getElement()),qs);
			els.stream().map(e1 -> new ElementNotExists(e1));
			return els;
	
//		TODO add variability for the (not)exists itself in the filter?
		} else if(e instanceof ElementFilter) { 
			
			List<Element> els = new ArrayList<Element>();			
			
			//first, recursion
			if(((ElementFilter) e).getExpr() instanceof E_Exists) {
				
				List<Element> l1 = getInVariability(
						((ExprFunctionOp) ((ElementFilter) e).getExpr()).getElement(),qs);
				l1.stream().map(e1 -> new ElementFilter(new E_Exists(e1)));
				
				els.addAll(l1);
				
			} else if(((ElementFilter) e).getExpr() instanceof E_NotExists) {
				
				List<Element> l1 = getInVariability(
						((ExprFunctionOp) ((ElementFilter) e).getExpr()).getElement(),qs);
				l1.stream().map(e1 -> new ElementFilter(new E_NotExists(e1)));
				
				els.addAll(l1);
				
			} else {
				els.add(e);
			}
				
			int c = els.size();
//			then, the variability for the filter itself
			for (int i = 0; i < c; i++) {
				els.add(new ElementFilter(new E_LogicalNot(((ElementFilter) els.get(i)).getExpr())));
			}

			return els;
		
		} else if(e instanceof ElementGroup) {
			
			List<Element> els = new ArrayList<Element>();
			List<List<Element>> l1 = getInVariability(((ElementGroup) e).getElements(),qs);
			//optimization; here we do not have to calculate the one element powerset
			if(l1.stream().allMatch(l2 -> l2.size() == 1)) {
				
				els.add(e);				
			} else {
				
				List<List<Element>> l2 = oneElementPowerset(l1);
				
				for (List<Element> els2 : l2) {
//System.out.println();e.toString();
					ElementGroup e1 = new ElementGroup(); 
					e1.getElements().addAll(els2);

					els.add(e1);
				}
			}

			return els;
		
		} else if(e instanceof ElementMinus) { 
			
			List<Element> l1 = getInVariability(((ElementMinus) e).getMinusElement(),qs);
			
			List<Element> els = new ArrayList<Element>();
			els.addAll(l1);
			els.addAll(l1.stream().
					map(e1 -> new ElementMinus(e1)).collect(Collectors.toList()));			
			return els;
		
		}  else if(e instanceof ElementNamedGraph) {
			
			ElementNamedGraph e1 = (ElementNamedGraph) e;
			List<Element> els = getInVariability(e1.getElement(),qs);
			els.stream().map(e2 -> new ElementNamedGraph(e1.getGraphNameNode(), e2));
			return els;	
			
		} else if(e instanceof ElementOptional) {
			
			List<Element> l1 = getInVariability(((ElementOptional) e).getOptionalElement(),qs);
			
			List<Element> els = new ArrayList<Element>();
			els.addAll(l1);
			els.addAll(l1.stream().
					map(e1 -> new ElementFilter(new E_NotExists(e1))).collect(Collectors.toList()));			
			return els;
		
		} else if(e instanceof ElementService) {
			
			ElementService e1 = (ElementService) e;
			List<Element> els = getInVariability(e1.getElement(),qs);
			els.stream().map(e2 -> new ElementService(e1.getServiceNode(), e2, e1.getSilent()));
			return els;	
			
		} else if(e instanceof ElementSubQuery) {
			
			List<Element> els = getInVariability(((ElementSubQuery) e).getQuery().getQueryPattern(),qs);
			els.stream().map(e2 -> {
				Query q = ((ElementSubQuery) e).getQuery().cloneQuery(); 
				q.setQueryPattern(e2); 
				return q;});
			return els;	
			
		} else if(e instanceof ElementUnion) {

			List<Element> els = new ArrayList<Element>();
			List<List<Element>> l1 = getInVariability(((ElementUnion) e).getElements(),qs);
			//optimization
			if(l1.stream().allMatch(l2 -> l2.size() == 1)) {
				
				for (List<Element> l : powerset(((ElementUnion) e).getElements())) {
					
					ElementGroup e1 = new ElementGroup();
					for (Element e2 : ((ElementUnion) e).getElements()) {
						e1.getElements().add(l.contains(e2) ? e2 :
							new ElementFilter(new E_NotExists(e2)));
					}

					els.add(e1);
				}			
				
			} else {
				
				List<List<Element>> l2 = oneElementPowerset(l1);
				for (List<Element> els2 : l2) {
					
					for (List<Element> l : powerset(els2)) {
						
						ElementGroup e1 = new ElementGroup();
						for (Element e2 : ((ElementUnion) e).getElements()) {
							e1.getElements().add(l.contains(e2) ? e2 :
								new ElementFilter(new E_NotExists(e2)));
						}

						els.add(e1);
					}			
				
				}
			}

			return els;
		
		}	
		
//		jena sparql syntax Element that are treated correctly in default case:
//		ElementAssign, ElementBind: no jena sparql syntax Elements in expressions
//		(except in (not) exists, but that may only occur in filters - according to the spec)
//		ElementData: (looks as if it) represents rdf data, bindings of variables to nodes
//		maybe also the sparql values clause
//		ElementPathBlock, ElementTriplesBlock: bgps are no jena sparql syntax Elements
		
		List<Element> els = new ArrayList<Element>();
		els.add(e);		
		return els;	
		
	}

	private List<List<Element>> getInVariability(List<Element> elements, String queryString) {		
		
		List<List<Element>> els = new ArrayList<List<Element>>();		
		for (Element e : elements) 
			els.add(getInVariability(e, queryString));
	
		return els;
	}

	private List<Query> createConstructQueries(String selectOrAskQueryString, int datasetSizeMax) {
		
//		TODO check if it's a selectOrAsk ...

		Query q = QueryFactory.create(selectOrAskQueryString);
		Element p = q.getQueryPattern();

		List<Query> cqs = new ArrayList<Query>();
		
		for (Element e : getInVariability(p,selectOrAskQueryString)) {
			
			Query cq = QueryFactory.make();
			cq.setQueryConstructType();
			cq.setBaseURI(q.getBaseURI());
			cq.setPrefixMapping(q.getPrefixMapping());	
//			the WHERE clause is the original one, 
//			but possibly adapted to make the dataset more variable
			cq.setQueryPattern(e);                              
			cq.setLimit(datasetSizeMax > 0 ? datasetSizeMax : defaultDataLimit);
			
//			create the construct part of the query
			List<String> l1 = new ArrayList<String>();
			for (Element b: extractBGPs(p)) { //TODO make method dependent on kind of SELECT clause
				l1.add(b.toString());
			}
			
			cq.setConstructTemplate(QueryFactory.createTemplate("{"+ String.join(".", l1) +"}"));
			
			cqs.add(cq);
		}
				
//		TODO We currently only consider BGPs in the WHERE clause
//		TODO What if subgraphs, data sets etc. occur in the SELECT clause?

		return cqs;		
	}

	public void extractQueryDataAndResults(String logEndpoint, int datasetSizeMax) {
		
		//clean data directory 
		File d1 = new File(Utils.DATA_DIR);
//		for(File d2: d1.listFiles()) {
//			for(File file: d2.listFiles()) {
//			    if (file.getName().endsWith(Utils.CONSTRUCT_QUERIES_FILE_EXT) ||
//			    		file.getName().endsWith(Utils.QUERY_DATA_FILE_EXT) || 
//			    		file.getName().endsWith(Utils.QUERY_RESULT_FILE_EXT) ) {
//			        file.delete();
//			    }
//			}
//		}

//		for each config directory
		for(File d2: d1.listFiles()) {//TODO does not work like this for qid case. extract inners to method
			for(File f: d2.listFiles()) {
			    
			    if (f.getName().endsWith(Utils.CONSTRUCT_QUERIES_FILE_EXT) ||
			    		f.getName().endsWith(Utils.QUERY_DATA_FILE_EXT) || 
			    		f.getName().endsWith(Utils.QUERY_RESULT_FILE_EXT) ) {
			        f.delete();
			    }
			}

			List<String[]> lqs = new ArrayList<String[]>();
			
			try { 		
				
				for(File f: d2.listFiles()) {
					lqs.add(Utils.readQueryFile(f));
				}	
				
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			for (String[] lq: lqs) {
				
				int hasData = 0; 
				
				String qid = lq[0];System.out.println(qid);
				String q = lq[1];			
				//some queries are erroneous; missing whitespace
				q = q.replace("FROM <http://dbpedia.org>", " FROM <http://dbpedia.org> ");

				List<Query> cqs = createConstructQueries(q, datasetSizeMax);
//					uncomment the following line to get a file with all the cqs
				Utils.writeConstructQueriesFile(d2,qid,cqs);
				
				for (Query cq : cqs) {
//						Query cq = toConstructQuery(q, datasetSizeMax); //System.out.println("------------------ ");System.out.println(cq);
//						System.out.println("------------------ ");System.out.println(cq);
//						
					QueryEngineHTTP qe = null;
					try {		
						
						qe = (QueryEngineHTTP) QueryExecutionFactory.sparqlService(logEndpoint, cq);
			            qe.addParam("timeout", "10000") ;

			            Model m = qe.execConstruct();
			            
			            if(m.listStatements().hasNext()) {
			            		hasData++;
			            		Utils.writeQueryDataFile(d2,qid, m);
			            }

			        } catch (Exception e) { 

			        	System.out.println("EXCEPTION " + qid);e.printStackTrace();
//				        	System.out.println("------------------ Query failed START");
//				        	System.out.println(qid);
//				        	System.out.println(e);
//				        	System.out.println("------------------ ");
//				        	System.out.println(q);
//				        	System.out.println("------------------ Query failed END");
		            	//just leave this in currently to be able to look at the query
//			            	(new File(Utils.getQueryFilePath(qid))).delete();
			        	
			        } finally {
			        		if(qe != null) qe.close();
			        }
				}
				
				System.out.println("variability queries with data: "+hasData+ "/" +cqs.size() );
				if(hasData == 0) { 	
//		            	System.out.println("NO DATA "+ qid);
//		            	System.out.println(query);
	            	
//		            	delete other files
//		            	(new File(Utils.getQueryFilePath(qid))).delete();
	            		continue;
				}
				
				InputStream in = null; Model m;
				try {
					
					in = new FileInputStream(new File(d2.getPath() + File.separator + Utils.getQueryDataFileName(qid)));
					 
					// Create an empty in-memory model and populate it from the data
					m = ModelFactory.createMemModelMaker().createModel(qid+"");
					m.read(in, null); // null base URI, since model URIs are absolute
					
					in.close();

				} catch (Exception e) {
					e.printStackTrace();
					continue;
				} 

				Query query = QueryFactory.create(q);
//					we cannot allow/test this given our restricted dataset size
				query.setOffset(0);

				QueryExecution qe1 = QueryExecutionFactory.create(query, m);
				ResultSet rs = qe1.execSelect();
				 
				if(!rs.hasNext()) {
					
					System.out.println("NO RESULT "+ qid);
//			            	System.out.println(q);
	            	
	            	//delete files
//			            (new File(Utils.getQueryFilePath(qid))).delete();
//						(new File(Utils.getQueryDataFilePath(qid))).delete();
	            	
	            } else {
	            	Utils.writeQueryResultFile(d2, qid, rs);
	            }

				qe1.close();
			}
		}
		
	}
	
	public static void main(String[] args) {
		LogQueryDataExtractor de = new LogQueryDataExtractor();
		de.extractQueryDataAndResults("http://dbpedia.org/sparql", 0);
		
//		Integer[] i1 = {1,2,3};
//		Integer[] i2 = {11,22,33};
//		Integer[] i3 = {111,222,333};
//		
//		List<List<Integer>> list = new ArrayList<List<Integer>>();
//		list.add(Arrays.asList(i1));
//		list.add(Arrays.asList(i2));
//		list.add(Arrays.asList(i3));
//System.out.println(list);
//		System.out.println(de.oneElementPowerset(list));
	}


}
