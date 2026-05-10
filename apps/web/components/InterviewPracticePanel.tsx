"use client";

import { CheckCircle2, ClipboardCheck, Loader2, MessageSquareText, Send } from "lucide-react";
import { AnswerFeedback, InterviewQuestion } from "@/lib/mockAssessment";
import { EmptyState } from "./EmptyState";
import { EvidenceRefs } from "./EvidenceRefs";

export function InterviewPracticePanel({
  questions,
  activeQuestion,
  answer,
  answerFeedback,
  isSubmittingAnswer,
  onActiveQuestionChange,
  onAnswerChange,
  onClearAnswerFeedback,
  onSubmitAnswer
}: {
  questions: InterviewQuestion[];
  activeQuestion: InterviewQuestion | undefined;
  answer: string;
  answerFeedback: AnswerFeedback | null;
  isSubmittingAnswer: boolean;
  onActiveQuestionChange: (questionId: string) => void;
  onAnswerChange: (value: string) => void;
  onClearAnswerFeedback: () => void;
  onSubmitAnswer: () => void;
}) {
  return (
    <section className="panel" id="interview">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">{questions.length || 0} questions</p>
          <h2>Interview practice</h2>
        </div>
        <ClipboardCheck size={22} aria-hidden="true" />
      </div>

      {activeQuestion ? (
        <div className="interview-layout">
          <div className="question-list">
            {questions.map((question) => (
              <button
                className={question.id === activeQuestion.id ? "question-tab active" : "question-tab"}
                key={question.id}
                type="button"
                onClick={() => {
                  onActiveQuestionChange(question.id);
                  onClearAnswerFeedback();
                }}
              >
                <span>{question.category}</span>
                <small>{question.difficulty}</small>
              </button>
            ))}
          </div>

          <div className="answer-panel">
            <div className="question-copy">
              <span>{activeQuestion.category}</span>
              <p>{activeQuestion.questionText}</p>
            </div>

            <div className="signal-list">
              {activeQuestion.expectedSignals.map((signal) => (
                <span key={signal}>{signal}</span>
              ))}
            </div>

            <EvidenceRefs ids={activeQuestion.sourceContextIds} />

            <textarea
              className="answer-textarea"
              placeholder="Type your answer here."
              value={answer}
              onChange={(event) => onAnswerChange(event.target.value)}
            />

            <button
              className="primary-button compact"
              type="button"
              onClick={onSubmitAnswer}
              disabled={isSubmittingAnswer || !answer.trim()}
            >
              {isSubmittingAnswer ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <Send size={16} aria-hidden="true" />}
              {isSubmittingAnswer ? "Scoring answer" : "Get feedback"}
            </button>

            {answerFeedback ? <AnswerFeedbackPanel answerFeedback={answerFeedback} /> : null}
          </div>
        </div>
      ) : (
        <EmptyState
          icon={<MessageSquareText size={24} aria-hidden="true" />}
          title="Questions are waiting"
          text="Analyze a resume to generate a role-aware interview set."
        />
      )}
    </section>
  );
}

function AnswerFeedbackPanel({ answerFeedback }: { answerFeedback: AnswerFeedback }) {
  return (
    <div className="feedback">
      <div className="feedback-score">
        <CheckCircle2 size={18} aria-hidden="true" />
        <strong>{answerFeedback.score}</strong>
      </div>
      <div>
        <p>{answerFeedback.summary}</p>
        <span>{answerFeedback.nextStep}</span>
      </div>
      {answerFeedback.strengths?.length || answerFeedback.gaps?.length ? (
        <div className="feedback-details">
          {answerFeedback.strengths?.length ? (
            <div>
              <strong>Working</strong>
              {answerFeedback.strengths.map((item) => <span key={item}>{item}</span>)}
            </div>
          ) : null}
          {answerFeedback.gaps?.length ? (
            <div>
              <strong>Improve</strong>
              {answerFeedback.gaps.map((item) => <span key={item}>{item}</span>)}
            </div>
          ) : null}
        </div>
      ) : null}
      {answerFeedback.followUpQuestion ? (
        <div className="feedback-follow-up">
          <strong>Follow-up</strong>
          <span>{answerFeedback.followUpQuestion}</span>
        </div>
      ) : null}
      <EvidenceRefs ids={answerFeedback.sourceContextIds} />
    </div>
  );
}
