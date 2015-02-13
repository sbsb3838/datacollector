/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.stage.processor.splitter;

import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.ErrorCode;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.Label;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.api.base.SingleLaneRecordProcessor;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.util.StageLibError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@GenerateResourceBundle
@StageDef(
    version="1.0.0",
    label="Field Splitter",
    description = "Splits a string field based on a separator character",
    icon="splitter.png"
)
@ConfigGroups(SplitterProcessor.Groups.class)
public class SplitterProcessor extends SingleLaneRecordProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SplitterProcessor.class);

  public enum Groups implements Label {
    FIELD_SPLITTER;

    @Override
    public String getLabel() {
      return "Split";
    }

  }

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Field to Split",
      description = "Split string fields. You can enter multiple fields to split with the same separator.",
      displayPosition = 10,
      group = "FIELD_SPLITTER"
  )
  public String fieldPath;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = " ",
      label = "Separator",
      description = "A single character. Use ^ for space.",
      displayPosition = 20,
      group = "FIELD_SPLITTER"
  )
  public char separator;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.LIST,
      label = "New Split Fields",
      description="New fields to pass split data. The last field includes any remaining unsplit data.",
      displayPosition = 30,
      group = "FIELD_SPLITTER"
  )
  public List<String> fieldPathsForSplits;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "DISCARD",
      label = "Not Enough Splits ",
      description="Action for data that cannot be split as configured",
      displayPosition = 40,
      group = "FIELD_SPLITTER"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = OnNotEnoughSplitsChooserValues.class)
  public OnNotEnoughSplits onNotEnoughSplits;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "REMOVE",
      label = "Original Field",
      description="Action for the original field being split",
      displayPosition = 50,
      group = "FIELD_SPLITTER"
  )
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = OriginalFieldActionChooserValues.class)
  public OriginalFieldAction originalFieldAction;

  private boolean removeUnsplitValue;

  private String separatorStr;
  private String[] fieldPaths;

  @Override
  protected void init() throws StageException {
    super.init();

    if (fieldPathsForSplits.size() < 2) {
      throw new StageException(StageLibError.LIB_0700);
    }

    separatorStr = (separator == '^') ? " " : "" + separator;

    //forcing a fastpath for String.split()
    if (".$|()[{^?*+\\".contains(separatorStr)) {
      separatorStr = "\\" + separatorStr;
    }

    fieldPaths = fieldPathsForSplits.toArray(new String[fieldPathsForSplits.size()]);

    removeUnsplitValue = originalFieldAction == OriginalFieldAction.REMOVE;
  }

  @Override
  protected void process(Record record, SingleLaneBatchMaker batchMaker) throws StageException {
    Field field = record.get(fieldPath);
    String[] splits = null;
    ErrorCode error = null;
    if (field == null || field.getValue() == null) {
      error = StageLibError.LIB_0701;
    } else {
      String str = field.getValueAsString();
      splits = str.split(separatorStr, fieldPaths.length);
      if (splits.length < fieldPaths.length) {
        error = StageLibError.LIB_0702;
      }
    }
    if (error == null || onNotEnoughSplits == OnNotEnoughSplits.CONTINUE) {
      for (int i = 0; i < fieldPaths.length; i++) {
        if (splits != null && splits.length > i) {
          record.set(fieldPaths[i], Field.create(splits[i]));
        } else {
          record.set(fieldPaths[i], Field.create(Field.Type.STRING, null));
        }
      }
      if (removeUnsplitValue) {
        record.delete(fieldPath);
      }
      batchMaker.addRecord(record);
    } else {
      switch (onNotEnoughSplits) {
        case DISCARD:
          LOG.debug(error.getMessage(), record, fieldPath);
          break;
        case TO_ERROR:
          getContext().toError(record, error, record, fieldPath);
          break;
        case STOP_PIPELINE:
          throw new StageException(error, record, fieldPath);
        default:
          throw new IllegalStateException(Utils.format("It should not happen, error={}, onNotEnoughSplits={}", error,
                                                       onNotEnoughSplits));
      }
    }
  }

}
