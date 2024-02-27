# Design Journal for isn-ref-impl

## Design constraints, recommendatins and decisions

## Design (draft)

This outlines emerging design (which needs peer review)

### Retraction and asserting new facts about existing signals

- Any party can assert new facts (which may be counter to previously asserted facts - or in effect disagree with previous assertions) about a signal originally contributed by another party. In this case a new signal is created which associates itself to the same correlation-id as the original signal
- Any party can assert new facts (or in effect disagree with previous assertions) about a signal originally contributed by themselves. In this case a new signal is created associated to a prior correlation-id
- Any party which contributes an initial signal but would like to retract the entire signal (not simply assert new facts about it) must:
  - assert the fact that the original signal in a 'thread' (e.g. sharing a correlation-id) has a new category 'isn-retracted-signal' which is a reserved isn specific category
  - assert the fact that they are retracting the signal thread is being retracted by specifying an informative object/predicate (N.B. name/summary via API) structure clearly indicating this (e.g. 'brazil nut pre-notification retracted due to original admin error')

The isn network machinery will handle the retraction by:
- annotating the signal (marking it up) evidently as 'retracted' in the web dashboard
- distributing the new retraction signal on the thread informing participants the original signal was incorrect and the thread must be retracted (specifically via the newly asserted object/predicate and 'isn-retracted-signal' category - N.B. it is up to participants to factor the retraction into their workflows across ISNs and the associated domains
- it _may_ be necessary to add a timed expiry to the signal thread so the entire thread is archived and will not be cause confusion within the network - the setting of the expiry may need to be:
  - set by the party retracting the signal (but must follow a published network convention for notice period)
  - be configurable so the system can automatically set it (but must follow a published network convention for notice period) 

