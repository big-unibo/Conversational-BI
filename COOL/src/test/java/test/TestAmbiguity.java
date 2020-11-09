package test;

import com.google.common.collect.Lists;
import it.unibo.conversational.Validator;
import it.unibo.conversational.algorithms.Parser;
import it.unibo.conversational.database.Config;
import it.unibo.conversational.database.Cube;
import it.unibo.conversational.database.DBmanager;
import it.unibo.conversational.datatypes.Mapping;
import it.unibo.conversational.datatypes.Ngram;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Test the validation accuracy. */
public class TestAmbiguity {

  private final Cube foodmart = Config.getCube("sales_fact_1997");
  private final Cube ssb = Config.getCube("lineorder2");

  @AfterEach
  public void after() {
    Parser.resetIds();
  }

  private String ambiguitiesToString(final Mapping m) {
    return m.getAnnotatedNgrams().stream().map(Ngram::getAnnotations).collect(Collectors.toList()).toString();
  }

  private void checkEquals(final Cube cube, final Mapping ambiguousMapping, final Mapping correctSentence, final boolean checkEquals) throws IOException {
    assertEquals(1, ambiguousMapping.ngrams.size());
    assertEquals(correctSentence.bestNgram.countNode(), ambiguousMapping.bestNgram.countNode());
    if (checkEquals) {
      assertEquals(1.0, correctSentence.similarity(ambiguousMapping), 0.001, correctSentence.toString() + "\n" + ambiguousMapping.toString());
    } else {
      System.out.println(ambiguousMapping.toStringTree());
      System.out.println(correctSentence.toStringTree());
    }
    final List<Ngram> ann = ambiguousMapping.getAnnotatedNgrams();
    assertTrue(ann.isEmpty(), ann.toString());
    try {
      DBmanager.executeDataQuery(cube, Parser.getSQLQuery(cube, ambiguousMapping), res -> {});
    } catch (final Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  private void checkQuery(final Cube cube, final Mapping correctSentence, final String nlp, final int ambiguousNgrams, final List<Pair<String, String>> disambiguations) {
    checkQuery(cube, correctSentence, nlp, ambiguousNgrams, disambiguations, true);
  }

  private void checkQuery(final Cube cube, final Mapping correctSentence, final String nlp, final int ambiguousNgrams, final List<Pair<String, String>> disambiguations, final boolean checkEquals) {
    Parser.TEST = true;
    permutations(disambiguations).forEach(l -> {
      // System.out.println(l);
      Parser.resetIds();
      try {
        final Mapping ambiguousMapping = Validator.parseAndTranslate(cube, nlp);
        assertEquals(ambiguousNgrams, ambiguousMapping.getAnnotatedNgrams().size(), ambiguitiesToString(ambiguousMapping));
        for (Pair<String, String> d: l) {
          ambiguousMapping.disambiguate(d.getLeft(), d.getRight());
          try {
            new JSONObject(ambiguousMapping.toJSON(cube, nlp));
          } catch (final Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
          }
        }
        checkEquals(cube, ambiguousMapping, correctSentence, checkEquals);
      } catch (final Exception e) {
        e.printStackTrace();
        fail(e.getMessage());
      }
    });
    Parser.TEST = false;
  }

  private <E> List<List<E>> permutations(List<E> original) {
    if (original.isEmpty()) {
      final List<List<E>> result = new ArrayList<>();
      result.add(new ArrayList<>());
      return result;
    }
    final E firstElement = original.remove(0);
    final List<List<E>> returnValue = new ArrayList<>();
    final List<List<E>> permutations = permutations(original);
    for (List<E> smallerPermutated : permutations) {
      for (int index = 0; index <= smallerPermutated.size(); index++) {
        List<E> temp = new ArrayList<>(smallerPermutated);
        temp.add(index, firstElement);
        returnValue.add(temp);
      }
    }
    return returnValue;
  }

  private void test(final Cube cube, final String nl, final String gc, final String sc, final String mc, final int expectedAnnotations) throws Exception {
    final Mapping ambiguousMapping = Validator.parseAndTranslate(cube, nl);
    new JSONObject(ambiguousMapping.toJSON(cube, nl));
    assertEquals(ambiguousMapping.getAnnotatedNgrams().size(), expectedAnnotations, ambiguitiesToString(ambiguousMapping));
    final Mapping correctSentence = Validator.getBest(cube, gc, sc, mc);
    new JSONObject(correctSentence.toJSON(cube));
    for (int i = 0; i < 4; i++) {
      Parser.automaticDisambiguate(ambiguousMapping);
      new JSONObject(ambiguousMapping.toJSON(cube, nl));
    }
    checkEquals(cube, ambiguousMapping, correctSentence, true);
  }

  // /** Test disambiguation. */
  // @Test
  // public void foodmartTest7() throws Exception {
  //   test("unit sales by month in 2010 for Atomic Mints USA by store", "the_month, store_id", "the_year = 2010 and product_name = Atomic Mints and country = USA", "avg unit_sales");
  // }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest01() throws Exception {
    test(foodmart, "sum unit sales by media type for USA", "media_type", "country = USA", "sum unit_sales", 1);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest02() throws Exception {
    test(foodmart, "sum unit sales by media type for USA and Mexico", "media_type", "country = USA and country = Mexico", "sum unit_sales", 2);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest03() throws Exception {
    test(foodmart, "sum unit sales by media type for Salem", "media_type", "city = Salem", "sum unit_sales", 1);
  }

  
  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest04() throws Exception {
    test(foodmart, "unit sales for USA", "", "country = USA", "avg unit_sales", 2);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest05() throws Exception {
    test(foodmart, "unit sales for USA and state province Sheri Nowmere", "", "country = USA and state_province = BC", "avg unit_sales", 3);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest06() throws Exception {
    test(foodmart, "unit sales for USA and Sheri Nowmere as province", "", "country = USA and state_province = BC", "avg unit_sales", 3);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest08() throws Exception {
    test(foodmart, "unit sales in 2010 and Atomic Mints", "", "the_year = 2010 and product_name = Atomic Mints", "avg unit_sales", 1);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest09() throws Exception {
    test(foodmart, "unit sales for Sheri Nowmere", "", "fullname = Sheri Nowmer", "avg unit_sales", 1);
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest10() throws Exception {
    test(foodmart, "sum unit sales by media type for USA country", "media_type", "country = USA", "sum unit_sales", 0);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest11() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "", "", "max unit_sales");
    checkQuery(foodmart, correctSentence, "unit sales for Salem", 2, Lists.newArrayList(Pair.of("i0", "max"), Pair.of("i1", "drop")));
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest12() throws Exception {
    test(foodmart, "by product_id unit_sales by product_category", "product_id", "", "avg unit_sales", 2);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest13() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "product_id, product_category", "", "avg unit_sales");
    checkQuery(foodmart, correctSentence, "by product_id unit_sales by product_category", 2, Lists.newArrayList(Pair.of("i0", "avg"), Pair.of("u0", "add")));
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest14() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "product_id", "", "avg unit_sales");
    checkQuery(foodmart, correctSentence, "by product_id unit_sales by product_category", 2, Lists.newArrayList(Pair.of("i0", "avg"), Pair.of("u0", "drop")));
  }

  
  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest16() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "", "", "max unit_sales");
    checkQuery(foodmart, correctSentence, "unit sales store sales", 2, Lists.newArrayList(Pair.of("i0", "max"), Pair.of("i1", "drop")));
  }

  /** Test disambiguation.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest17() throws Exception {
    test(foodmart, "store sales e store cost for USA", "", "country = USA", "avg store_sales, avg store_cost", 3);
  }

  /** Test tokenization.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest18() throws Exception {
    test(foodmart, "total unit sales for gender=F", "", "gender = F", "sum unit_sales", 0);
  }

  /** Test tokenization.
   * @throws Exception in case of error 
   */
  @Test
  public void foodmartTest19() throws Exception {
    test(foodmart, "count sales fact by customers", "customer_id", "", "count sales_fact_1997", 0);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest20() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "product_id", "", "avg unit_sales");
    checkQuery(foodmart, correctSentence, "unit_sales by product_id store_cost", 2, Lists.newArrayList(Pair.of("i0", "avg"), Pair.of("u0", "drop")));
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest21() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "product_id", "", "avg store_cost, avg unit_sales");
    checkQuery(foodmart, correctSentence, "unit_sales by product_id store_cost", 2, Lists.newArrayList(Pair.of("i1", "avg"), Pair.of("i0", "avg"), Pair.of("u0", "add")), false);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest22() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "", "product_name = Atomic Mints", "avg unit_sales");
    checkQuery(foodmart, correctSentence, "Atomic Mints unit_sales Atomic Mints", 2, Lists.newArrayList(Pair.of("i0", "avg"), Pair.of("u0", "drop")));
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest23() throws Exception {
    final Mapping correctSentence = Validator.getBest(foodmart, "", "product_name = Atomic Mints and product_name = Atomic Mints", "avg unit_sales");
    checkQuery(foodmart, correctSentence, "Atomic Mints unit_sales Atomic Mints", 2, Lists.newArrayList(Pair.of("i0", "avg"), Pair.of("u0", "add")));
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest24() throws Exception {
    test(foodmart, "units sales by country for 2015 as year", "country", "the_year = 2015", "avg unit_sales", 1);
    test(foodmart, "sum unit sales by country for 2015 as year", "country", "the_year = 2015", "sum unit_sales", 0);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest25() throws Exception {
    test(foodmart, "store sales for Sheri Nowmer as customer", "", "customer_id = -1", "avg store_sales", 2);
    test(foodmart, "store sales for 1 as fullname", "", "fullname = A. Catherine Binkley", "avg store_sales", 2);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest26() throws Exception {
    test(foodmart, "max store sales by product family where occupation is usa and group by promotion name", "product_family", "occupation = Clerical", "max store_sales", 2);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest27() throws Exception {
    test(foodmart, "sum store sales where category is new york", "", "", "sum store_sales", 0);
    test(foodmart, "sum store sales where category is Beer and wine", "", "product_category = Beer and Wine", "sum store_sales", 0);
  }

  /** Test disambiguation.
   * @throws Exception in case of error
   */
  @Test
  public void foodmartTest28() throws Exception {
    test(foodmart, "sum store sales where occupation professional", "", "occupation = professional", "sum store_sales", 0);
    test(foodmart, "sum store sales with occupation professional", "", "occupation = professional", "sum store_sales", 0);
  }

  @Test
  public void ssbTest01() throws Exception {
    test(ssb, "sum quantity for Apolonia Gerlach", "", "customer = Apolonia Gerlach", "sum quantity", 0);
  }
}
