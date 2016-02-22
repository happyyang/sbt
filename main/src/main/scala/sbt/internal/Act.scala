/* sbt -- Simple Build Tool
 * Copyright 2011  Mark Harrah
 */
package sbt
package internal

import Def.{ showRelativeKey, ScopedKey }
import Keys.sessionSettings
import sbt.internal.util.complete.{ DefaultParsers, Parser }
import Aggregation.{ KeyValue, Values }
import DefaultParsers._
import sbt.internal.util.Types.idFun
import java.net.URI
import sbt.internal.CommandStrings.{ MultiTaskCommand, ShowCommand }
import sbt.internal.util.{ AttributeEntry, AttributeKey, AttributeMap, IMap, Settings, Show, Util }

final class ParsedKey(val key: ScopedKey[_], val mask: ScopeMask)

object Act {
  val GlobalString = "*"

  // this does not take aggregation into account
  def scopedKey(index: KeyIndex, current: ProjectRef, defaultConfigs: Option[ResolvedReference] => Seq[String],
    keyMap: Map[String, AttributeKey[_]], data: Settings[Scope]): Parser[ScopedKey[_]] =
    scopedKeySelected(index, current, defaultConfigs, keyMap, data).map(_.key)

  // the index should be an aggregated index for proper tab completion
  def scopedKeyAggregated(current: ProjectRef, defaultConfigs: Option[ResolvedReference] => Seq[String], structure: BuildStructure): KeysParser =
    for (selected <- scopedKeySelected(structure.index.aggregateKeyIndex, current, defaultConfigs, structure.index.keyMap, structure.data)) yield Aggregation.aggregate(selected.key, selected.mask, structure.extra)

  def scopedKeySelected(index: KeyIndex, current: ProjectRef, defaultConfigs: Option[ResolvedReference] => Seq[String],
    keyMap: Map[String, AttributeKey[_]], data: Settings[Scope]): Parser[ParsedKey] =
    scopedKeyFull(index, current, defaultConfigs, keyMap) flatMap { choices =>
      select(choices, data)(showRelativeKey(current, index.buildURIs.size > 1))
    }

  def scopedKeyFull(index: KeyIndex, current: ProjectRef, defaultConfigs: Option[ResolvedReference] => Seq[String], keyMap: Map[String, AttributeKey[_]]): Parser[Seq[Parser[ParsedKey]]] =
    {
      def taskKeyExtra(proj: Option[ResolvedReference], confAmb: ParsedAxis[String], baseMask: ScopeMask): Seq[Parser[ParsedKey]] =
        for {
          conf <- configs(confAmb, defaultConfigs, proj, index)
        } yield for {
          taskAmb <- taskAxis(conf, index.tasks(proj, conf), keyMap)
          task = resolveTask(taskAmb)
          key <- key(index, proj, conf, task, keyMap)
          extra <- extraAxis(keyMap, IMap.empty)
        } yield {
          val mask = baseMask.copy(task = taskAmb.isExplicit, extra = true)
          new ParsedKey(makeScopedKey(proj, conf, task, extra, key), mask)
        }

      val projectKeys =
        for {
          rawProject <- optProjectRef(index, current)
          proj = resolveProject(rawProject, current)
          confAmb <- config(index configs proj)
          partialMask = ScopeMask(rawProject.isExplicit, confAmb.isExplicit, false, false)
        } yield taskKeyExtra(proj, confAmb, partialMask)

      val build = Some(BuildRef(current.build))
      val buildKeys =
        for {
          confAmb <- config(index configs build)
          partialMask = ScopeMask(false, confAmb.isExplicit, false, false)
        } yield taskKeyExtra(build, confAmb, partialMask)

      buildKeys combinedWith projectKeys map (_.flatten)
    }
  def makeScopedKey(proj: Option[ResolvedReference], conf: Option[String], task: Option[AttributeKey[_]], extra: ScopeAxis[AttributeMap], key: AttributeKey[_]): ScopedKey[_] =
    ScopedKey(Scope(toAxis(proj, Global), toAxis(conf map ConfigKey.apply, Global), toAxis(task, Global), extra), key)

  def select(allKeys: Seq[Parser[ParsedKey]], data: Settings[Scope])(implicit show: Show[ScopedKey[_]]): Parser[ParsedKey] =
    seq(allKeys) flatMap { ss =>
      val default = ss.headOption match {
        case None    => noValidKeys
        case Some(x) => success(x)
      }
      selectFromValid(ss filter isValid(data), default)
    }
  def selectFromValid(ss: Seq[ParsedKey], default: Parser[ParsedKey])(implicit show: Show[ScopedKey[_]]): Parser[ParsedKey] =
    selectByTask(selectByConfig(ss)) match {
      case Seq()       => default
      case Seq(single) => success(single)
      case multi       => failure("Ambiguous keys: " + showAmbiguous(keys(multi)))
    }
  private[this] def keys(ss: Seq[ParsedKey]): Seq[ScopedKey[_]] = ss.map(_.key)
  def selectByConfig(ss: Seq[ParsedKey]): Seq[ParsedKey] =
    ss match {
      case Seq() => Nil
      case Seq(x, tail @ _*) => // select the first configuration containing a valid key
        tail.takeWhile(_.key.scope.config == x.key.scope.config) match {
          case Seq() => x :: Nil
          case xs    => x +: xs
        }
    }
  def selectByTask(ss: Seq[ParsedKey]): Seq[ParsedKey] =
    {
      val (selects, globals) = ss.partition(_.key.scope.task.isSelect)
      if (globals.nonEmpty) globals else selects
    }

  def noValidKeys = failure("No such key.")

  def showAmbiguous(keys: Seq[ScopedKey[_]])(implicit show: Show[ScopedKey[_]]): String =
    keys.take(3).map(x => show(x)).mkString("", ", ", if (keys.size > 3) ", ..." else "")

  def isValid(data: Settings[Scope])(parsed: ParsedKey): Boolean =
    {
      val key = parsed.key
      data.definingScope(key.scope, key.key) == Some(key.scope)
    }

  def examples(p: Parser[String], exs: Set[String], label: String): Parser[String] =
    p !!! ("Expected " + label) examples exs
  def examplesStrict(p: Parser[String], exs: Set[String], label: String): Parser[String] =
    filterStrings(examples(p, exs, label), exs, label)

  def optionalAxis[T](p: Parser[T], ifNone: ScopeAxis[T]): Parser[ScopeAxis[T]] =
    p.? map { opt => toAxis(opt, ifNone) }
  def toAxis[T](opt: Option[T], ifNone: ScopeAxis[T]): ScopeAxis[T] =
    opt match { case Some(t) => Select(t); case None => ifNone }

  def config(confs: Set[String]): Parser[ParsedAxis[String]] =
    {
      val sep = ':' !!! "Expected ':' (if selecting a configuration)"
      token((GlobalString ^^^ ParsedGlobal | value(examples(ID, confs, "configuration"))) <~ sep) ?? Omitted
    }

  def configs(explicit: ParsedAxis[String], defaultConfigs: Option[ResolvedReference] => Seq[String], proj: Option[ResolvedReference], index: KeyIndex): Seq[Option[String]] =
    explicit match {
      case Omitted            => None +: defaultConfigurations(proj, index, defaultConfigs).flatMap(nonEmptyConfig(index, proj))
      case ParsedGlobal       => None :: Nil
      case pv: ParsedValue[x] => Some(pv.value) :: Nil
    }
  def defaultConfigurations(proj: Option[ResolvedReference], index: KeyIndex, defaultConfigs: Option[ResolvedReference] => Seq[String]): Seq[String] =
    if (index exists proj) defaultConfigs(proj) else Nil
  def nonEmptyConfig(index: KeyIndex, proj: Option[ResolvedReference]): String => Seq[Option[String]] = config =>
    if (index.isEmpty(proj, Some(config))) Nil else Some(config) :: Nil

  def key(index: KeyIndex, proj: Option[ResolvedReference], conf: Option[String], task: Option[AttributeKey[_]], keyMap: Map[String, AttributeKey[_]]): Parser[AttributeKey[_]] =
    {
      def dropHyphenated(keys: Set[String]): Set[String] = keys.filterNot(Util.hasHyphen)
      def keyParser(keys: Set[String]): Parser[AttributeKey[_]] =
        token(ID !!! "Expected key" examples dropHyphenated(keys)) flatMap { keyString =>
          getKey(keyMap, keyString, idFun)
        }
      keyParser(index.keys(proj, conf, task))
    }

  def getKey[T](keyMap: Map[String, AttributeKey[_]], keyString: String, f: AttributeKey[_] => T): Parser[T] =
    keyMap.get(keyString) match {
      case Some(k) => success(f(k))
      case None    => failure(Command.invalidValue("key", keyMap.keys)(keyString))
    }

  val spacedComma = token(OptSpace ~ ',' ~ OptSpace)

  def extraAxis(knownKeys: Map[String, AttributeKey[_]], knownValues: IMap[AttributeKey, Set]): Parser[ScopeAxis[AttributeMap]] =
    {
      val extrasP = extrasParser(knownKeys, knownValues)
      val extras = token('(', hide = _ == 1 && knownValues.isEmpty) ~> extrasP <~ token(')')
      optionalAxis(extras, Global)
    }

  def taskAxis(d: Option[String], tasks: Set[AttributeKey[_]], allKnown: Map[String, AttributeKey[_]]): Parser[ParsedAxis[AttributeKey[_]]] =
    {
      val taskSeq = tasks.toSeq
      def taskKeys(f: AttributeKey[_] => String): Seq[(String, AttributeKey[_])] = taskSeq.map(key => (f(key), key))
      val normKeys = taskKeys(_.label)
      val valid = allKnown ++ normKeys
      val suggested = normKeys.map(_._1).toSet
      val keyP = filterStrings(examples(ID, suggested, "key"), valid.keySet, "key") map valid
      (token(value(keyP) | GlobalString ^^^ ParsedGlobal) <~ token("::".id)) ?? Omitted
    }
  def resolveTask(task: ParsedAxis[AttributeKey[_]]): Option[AttributeKey[_]] =
    task match {
      case ParsedGlobal | Omitted                     => None
      case t: ParsedValue[AttributeKey[_]] @unchecked => Some(t.value)
    }

  def filterStrings(base: Parser[String], valid: Set[String], label: String): Parser[String] =
    base.filter(valid, Command.invalidValue(label, valid))

  def extrasParser(knownKeys: Map[String, AttributeKey[_]], knownValues: IMap[AttributeKey, Set]): Parser[AttributeMap] =
    {
      val validKeys = knownKeys.filter { case (_, key) => knownValues get key exists (_.nonEmpty) }
      if (validKeys.isEmpty)
        failure("No valid extra keys.")
      else
        rep1sep(extraParser(validKeys, knownValues), spacedComma) map AttributeMap.apply
    }

  def extraParser(knownKeys: Map[String, AttributeKey[_]], knownValues: IMap[AttributeKey, Set]): Parser[AttributeEntry[_]] =
    {
      val keyp = knownIDParser(knownKeys, "Not a valid extra key") <~ token(':' ~ OptSpace)
      keyp flatMap {
        case key: AttributeKey[t] =>
          val valueMap: Map[String, t] = knownValues(key).map(v => (v.toString, v)).toMap
          knownIDParser(valueMap, "extra value") map { value => AttributeEntry(key, value) }
      }
    }
  def knownIDParser[T](knownKeys: Map[String, T], label: String): Parser[T] =
    token(examplesStrict(ID, knownKeys.keys.toSet, label)) map knownKeys

  def knownPluginParser[T](knownPlugins: Map[String, T], label: String): Parser[T] = {
    val pluginLabelParser = rep1sep(ID, '.').map(_.mkString("."))
    token(examplesStrict(pluginLabelParser, knownPlugins.keys.toSet, label)) map knownPlugins
  }

  def projectRef(index: KeyIndex, currentBuild: URI): Parser[ParsedAxis[ResolvedReference]] =
    {
      val global = token(GlobalString ~ '/') ^^^ ParsedGlobal
      val trailing = '/' !!! "Expected '/' (if selecting a project)"
      global | value(resolvedReference(index, currentBuild, trailing))
    }
  def resolvedReference(index: KeyIndex, currentBuild: URI, trailing: Parser[_]): Parser[ResolvedReference] =
    {
      def projectID(uri: URI) = token(examplesStrict(ID, index projects uri, "project ID") <~ trailing)
      def projectRef(uri: URI) = projectID(uri) map { id => ProjectRef(uri, id) }

      val uris = index.buildURIs
      val resolvedURI = Uri(uris).map(uri => Scope.resolveBuild(currentBuild, uri))
      val buildRef = token('{' ~> resolvedURI <~ '}').?

      buildRef flatMap {
        case None      => projectRef(currentBuild)
        case Some(uri) => projectRef(uri) | token(trailing ^^^ BuildRef(uri))
      }
    }
  def optProjectRef(index: KeyIndex, current: ProjectRef): Parser[ParsedAxis[ResolvedReference]] =
    projectRef(index, current.build) ?? Omitted
  def resolveProject(parsed: ParsedAxis[ResolvedReference], current: ProjectRef): Option[ResolvedReference] =
    parsed match {
      case Omitted             => Some(current)
      case ParsedGlobal        => None
      case pv: ParsedValue[rr] => Some(pv.value)
    }

  def actParser(s: State): Parser[() => State] = requireSession(s, actParser0(s))

  private[this] def actParser0(state: State): Parser[() => State] =
    {
      val extracted = Project extract state
      import extracted.{ showKey, structure }
      import Aggregation.evaluatingParser
      actionParser.flatMap { action =>
        val akp = aggregatedKeyParser(extracted)
        def evaluate(kvs: Seq[ScopedKey[_]]): Parser[() => State] = {
          val preparedPairs = anyKeyValues(structure, kvs)
          val showConfig = Aggregation.defaultShow(state, showTasks = action == ShowAction)
          evaluatingParser(state, structure, showConfig)(preparedPairs) map { evaluate =>
            () => {
              val keyStrings = preparedPairs.map(pp => showKey(pp.key)).mkString(", ")
              state.log.debug("Evaluating tasks: " + keyStrings)
              evaluate()
            }
          }
        }
        action match {
          case SingleAction => akp flatMap evaluate
          case ShowAction | MultiAction =>
            rep1sep(akp, token(Space)).flatMap(kvss => evaluate(kvss.flatten))
        }
      }
    }

  private[this] final class ActAction
  private[this] final val ShowAction, MultiAction, SingleAction = new ActAction

  private[this] def actionParser: Parser[ActAction] =
    token(
      ((ShowCommand ^^^ ShowAction) |
        (MultiTaskCommand ^^^ MultiAction)) <~ Space
    ) ?? SingleAction

  @deprecated("No longer used.", "0.13.2")
  def showParser = token((ShowCommand ~ Space) ^^^ true) ?? false

  def scopedKeyParser(state: State): Parser[ScopedKey[_]] = scopedKeyParser(Project extract state)
  def scopedKeyParser(extracted: Extracted): Parser[ScopedKey[_]] = scopedKeyParser(extracted.structure, extracted.currentRef)
  def scopedKeyParser(structure: BuildStructure, currentRef: ProjectRef): Parser[ScopedKey[_]] =
    scopedKey(structure.index.keyIndex, currentRef, structure.extra.configurationsForAxis, structure.index.keyMap, structure.data)

  type KeysParser = Parser[Seq[ScopedKey[T]] forSome { type T }]
  def aggregatedKeyParser(state: State): KeysParser = aggregatedKeyParser(Project extract state)
  def aggregatedKeyParser(extracted: Extracted): KeysParser = aggregatedKeyParser(extracted.structure, extracted.currentRef)
  def aggregatedKeyParser(structure: BuildStructure, currentRef: ProjectRef): KeysParser =
    scopedKeyAggregated(currentRef, structure.extra.configurationsForAxis, structure)

  def keyValues[T](state: State)(keys: Seq[ScopedKey[T]]): Values[T] = keyValues(Project extract state)(keys)
  def keyValues[T](extracted: Extracted)(keys: Seq[ScopedKey[T]]): Values[T] = keyValues(extracted.structure)(keys)
  def keyValues[T](structure: BuildStructure)(keys: Seq[ScopedKey[T]]): Values[T] =
    keys.flatMap { key =>
      getValue(structure.data, key.scope, key.key) map { value => KeyValue(key, value) }
    }
  private[this] def anyKeyValues(structure: BuildStructure, keys: Seq[ScopedKey[_]]): Seq[KeyValue[_]] =
    keys.flatMap { key =>
      getValue(structure.data, key.scope, key.key) map { value => KeyValue(key, value) }
    }

  private[this] def getValue[T](data: Settings[Scope], scope: Scope, key: AttributeKey[T]): Option[T] =
    if (java.lang.Boolean.getBoolean("sbt.cli.nodelegation")) data.getDirect(scope, key) else data.get(scope, key)

  def requireSession[T](s: State, p: => Parser[T]): Parser[T] =
    if (s get sessionSettings isEmpty) failure("No project loaded") else p

  sealed trait ParsedAxis[+T] {
    final def isExplicit = this != Omitted
  }
  final object ParsedGlobal extends ParsedAxis[Nothing]
  final object Omitted extends ParsedAxis[Nothing]
  final class ParsedValue[T](val value: T) extends ParsedAxis[T]
  def value[T](t: Parser[T]): Parser[ParsedAxis[T]] = t map { v => new ParsedValue(v) }
}
